package com.example.autoschedule.service;

import com.example.autoschedule.ai.AiContext;
import com.example.autoschedule.ai.AiContext.*;
import com.example.autoschedule.ai.AiSchedulePipeline;
import com.example.autoschedule.ai.AiSchedulePipeline.AcceptedLine;
import com.example.autoschedule.dto.ScheduleDto;
import com.example.autoschedule.dto.ScheduleDto.ShiftBody;
import com.example.autoschedule.dto.TimeText;
import com.example.autoschedule.entity.*;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 근무표 생성 (POST /schedules/generate).
 * 1) 입력 수집(짧은 읽기 트랜잭션) → 2) Gemini 호출·검증·보정(트랜잭션 밖) → 3) DRAFT 로 저장(짧은 쓰기 트랜잭션).
 */
@Service
@RequiredArgsConstructor
public class AiScheduleService {

    private static final Pattern STAFF_REF = Pattern.compile("#(\\d+)");

    private final StoreRepository storeRepository;
    private final WorkerRepository workerRepository;
    private final PartRepository partRepository;
    private final WorkerAvailabilityRepository availabilityRepository;
    private final ScheduleRepository scheduleRepository;
    private final AiSchedulePipeline pipeline;
    private final PlatformTransactionManager transactionManager;

    public ScheduleDto.Response generate(Long userId, ScheduleDto.GenerateRequest req) {
        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setReadOnly(true);
        AiContext ctx = readTx.execute(status -> loadContext(userId, req));

        AiSchedulePipeline.Result result = pipeline.run(ctx);
        if (result.lines().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI가 조건에 맞는 근무를 만들지 못했습니다. 조건을 확인하고 다시 시도하세요.");
        }

        TransactionTemplate writeTx = new TransactionTemplate(transactionManager);
        return writeTx.execute(status -> save(userId, ctx, result));
    }

    // ---------------------------------------------------------------- 1단계: 입력 수집

    private AiContext loadContext(Long userId, ScheduleDto.GenerateRequest req) {
        Store store = storeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
        List<Worker> workers = workerRepository.findByStoreIdOrderById(store.getId());
        if (workers.isEmpty()) throw ApiException.badRequest("직원·알바생을 먼저 등록하세요");

        Map<Long, List<WorkerAvailability>> availability = availabilityRepository
                .findByWorkerIdIn(workers.stream().map(Worker::getId).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.getWorker().getId()));

        Map<Long, StaffInfo> staff = new LinkedHashMap<>();
        for (Worker w : workers) {
            Map<Weekday, List<Long>> partsByDay = new EnumMap<>(Weekday.class);
            Map<Weekday, int[]> fixedByDay = new EnumMap<>(Weekday.class);
            for (WorkerAvailability a : availability.getOrDefault(w.getId(), List.of())) {
                if (w.isFullTime()) {
                    if (a.getStartTime() != null && a.getEndTime() != null) {
                        fixedByDay.put(a.getDayOfWeek(), new int[]{Minutes.ofStart(a.getStartTime()),
                                Minutes.ofEnd(a.getStartTime(), a.getEndTime())});
                    }
                } else if (a.getPart() != null) {
                    partsByDay.computeIfAbsent(a.getDayOfWeek(), k -> new ArrayList<>()).add(a.getPart().getId());
                }
            }
            staff.put(w.getId(), new StaffInfo(w.getId(), w.getName(), w.isFullTime(), w.isMinor(), w.getHourlyWage(),
                    w.getTitle(), w.getContractWeeklyHours(), w.getMinWeeklyHours(), w.getMaxWeeklyHours(),
                    partsByDay, fixedByDay));
        }

        Map<Long, PartInfo> parts = new LinkedHashMap<>();
        for (Part p : partRepository.findByStoreIdOrderByStartTime(store.getId())) {
            parts.put(p.getId(), new PartInfo(p.getId(), p.getName(), Minutes.ofStart(p.getStartTime()),
                    Minutes.ofEnd(p.getStartTime(), p.getEndTime()), p.getBreakMinutes() == null ? 0 : p.getBreakMinutes()));
        }

        Map<Weekday, DayHours> hours = new EnumMap<>(Weekday.class);
        for (StoreHours h : store.getHours()) {
            hours.put(h.getDayOfWeek(), new DayHours(Minutes.ofStart(h.getOpenTime()),
                    Minutes.ofEnd(h.getOpenTime(), h.getCloseTime())));
        }
        List<DemandInfo> demand = store.getDemand().stream()
                .map(d -> new DemandInfo(d.getDayOfWeek(), Minutes.ofStart(d.getStartTime()),
                        Minutes.ofEnd(d.getStartTime(), d.getEndTime()), d.getRequiredCount()))
                .toList();

        return new AiContext(store.getId(), store.getScale(), YearMonth.from(req.getPeriodStart()), staff, parts, hours,
                demand, req.getWeeklyHolidayPay(), req.priorityEnum(), req.isFillContractHours());
    }

    // ---------------------------------------------------------------- 4단계: DRAFT 저장

    private ScheduleDto.Response save(Long userId, AiContext ctx, AiSchedulePipeline.Result result) {
        Store store = storeRepository.findWithLockByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
        if (!store.getId().equals(ctx.storeId())) throw ApiException.conflict("매장 정보가 변경되었습니다. 다시 시도하세요.");

        Map<Long, Worker> workers = workerRepository.findByStoreIdOrderById(store.getId()).stream()
                .collect(Collectors.toMap(Worker::getId, w -> w));
        Map<Long, Part> parts = partRepository.findByStoreIdOrderByStartTime(store.getId()).stream()
                .collect(Collectors.toMap(Part::getId, p -> p));

        // 같은 달 DRAFT 는 지우고 새로 만든다(CONFIRMED 유지)
        scheduleRepository.deleteAll(scheduleRepository.findByStoreIdAndTargetYearMonthAndStatus(
                store.getId(), ctx.month().toString(), Schedule.Status.DRAFT));
        scheduleRepository.flush();

        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setTargetYearMonth(ctx.month().toString());
        schedule.setStatus(Schedule.Status.DRAFT);
        schedule.setGenerationType(Schedule.GenerationType.AI);
        schedule.setWeeklyHolidayPay(ctx.weeklyHolidayPay());
        schedule.setPriority(ctx.priority());
        schedule.setUpdatedAt(LocalDateTime.now());

        List<WorkBlock> blocks = new ArrayList<>();
        for (AcceptedLine l : result.lines()) {
            Worker worker = workers.get(l.staffId());
            Part part = l.partId() == null ? null : parts.get(l.partId());
            if (worker == null || (l.partId() != null && part == null)) {
                throw ApiException.conflict("생성 중 구성원 또는 파트가 변경되었습니다. 다시 시도하세요.");
            }
            // 휴게시간·15분 단위·고용 형태 규칙은 수동 편집과 같은 정규화 경로를 쓴다.
            ShiftBody body = ShiftBody.builder()
                    .staffId(worker.getId())
                    .date(l.date().toString())
                    .start(TimeText.format(Minutes.toTime(l.startMin())))
                    .end(TimeText.formatEnd(Minutes.toTime(l.startMin()), Minutes.toTime(l.endMin())))
                    .partId(part == null ? null : String.valueOf(part.getId()))
                    .build();
            Shift shift = ScheduleService.normalize(body, worker, part);
            blocks.add(WorkBlock.of(shift));
            schedule.addShift(shift);
        }
        ShiftValidator.validate(blocks); // 최종 방어선

        ScheduleService.recompute(schedule);
        schedule.setAiComment(buildComment(result, workers));
        return ScheduleDto.Response.from(scheduleRepository.save(schedule));
    }

    /** notes 의 "#번호" 를 구성원 이름으로 복원하고, 서버가 아는 사실(제외 줄 수·미충원)을 덧붙인다. */
    private static String buildComment(AiSchedulePipeline.Result result, Map<Long, Worker> workers) {
        String notes = result.notes() == null ? "" : result.notes();
        Matcher m = STAFF_REF.matcher(notes);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Worker w = workers.get(Long.valueOf(m.group(1)));
            m.appendReplacement(sb, Matcher.quoteReplacement(w == null ? m.group() : w.getName()));
        }
        m.appendTail(sb);

        if (result.unfilledCount() > 0) {
            sb.append(" 인력이 부족해 채우지 못한 시간대가 ").append(result.unfilledCount()).append("건 있습니다.");
        }
        if (result.removedCount() > 0) {
            sb.append(" 규칙에 맞지 않아 제외된 근무가 ").append(result.removedCount()).append("건 있으니 확인해 주세요.");
        }
        return sb.toString().trim();
    }
}