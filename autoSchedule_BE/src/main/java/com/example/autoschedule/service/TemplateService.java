package com.example.autoschedule.service;

import com.example.autoschedule.dto.ScheduleDto;
import com.example.autoschedule.dto.ScheduleDto.LeaveBody;
import com.example.autoschedule.dto.ScheduleDto.ShiftBody;
import com.example.autoschedule.dto.TemplateDto;
import com.example.autoschedule.dto.TemplateDto.*;
import com.example.autoschedule.dto.TimeText;
import com.example.autoschedule.entity.*;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 템플릿: 근무표의 한 주(월~일)를 날짜 대신 요일(dayIndex)로 바꿔 JSON 으로 저장하고, 나중에 다른 달/주에 삽입한다.
 * 적용할 때는 구성원·파트가 그새 바뀌었을 수 있으므로 규칙(ShiftValidator)에 맞지 않거나 대상이 사라진 줄은 건너뛰고 개수를 알린다.
 */
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final StoreRepository storeRepository;
    private final TemplateRepository templateRepository;
    private final ScheduleRepository scheduleRepository;
    private final WorkerRepository workerRepository;
    private final PartRepository partRepository;
    private final WorkerAvailabilityRepository availabilityRepository;
    private final JsonMapper mapper;

    // ---------------------------------------------------------------- 목록 / 삭제

    @Transactional(readOnly = true)
    public List<TemplateDto.Response> list(Long userId) {
        // 매장이 아직 없으면 빈 목록
        return storeRepository.findByUserId(userId)
                .map(store -> templateRepository.findByStoreIdOrderByCreatedAtDesc(store.getId()).stream()
                        .map(this::toResponse).toList())
                .orElse(List.of());
    }

    @Transactional
    public void delete(Long userId, Long templateId) {
        Store store = myStore(userId);
        templateRepository.delete(ownedTemplate(store, templateId));
    }

    // ---------------------------------------------------------------- 저장: 한 주 -> JSON

    @Transactional
    public TemplateDto.Response save(Long userId, TemplateDto.SaveRequest req) {
        Store store = myStore(userId);
        Schedule schedule = scheduleRepository.findByIdAndStoreId(req.getScheduleId(), store.getId())
                .orElseThrow(() -> ApiException.notFound("근무표를 찾을 수 없습니다."));
        YearMonth month = YearMonth.parse(schedule.getTargetYearMonth());

        LocalDate weekStart = req.getWeekStart() != null ? req.getWeekStart() : firstFullWeek(month);
        if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.badRequest("주 시작일은 월요일이어야 합니다.");
        }
        if (!isFullWeekIn(weekStart, month)) {
            throw ApiException.badRequest("월~일 7일이 모두 그 달에 속하는 주만 템플릿으로 저장할 수 있습니다.");
        }

        Body body = toBody(schedule.getShifts(), weekStart);
        if (body.shifts().isEmpty() && body.leaves().isEmpty()) {
            throw ApiException.badRequest("근무가 없는 주는 템플릿으로 저장할 수 없습니다.");
        }

        Template t = new Template();
        t.setStore(store);
        t.setName(req.getName().trim());
        t.setShiftsJson(mapper.writeValueAsString(body));
        return toResponse(templateRepository.saveAndFlush(t));
    }

    // ---------------------------------------------------------------- 적용 1: 한 달 (api-spec 7.4)

    /**
     * 그 달 각 날짜에 같은 dayIndex 의 템플릿 근무를 복사한다(새 UUID). 연차는 그 요일이 처음 나오는 날에만 1건.
     * 같은 달 DRAFT 는 지우고 새로 만든다(CONFIRMED 는 유지).
     */
    @Transactional
    public ScheduleDto.Response applyToMonth(Long userId, Long templateId, TemplateDto.ApplyRequest req) {
        Store store = storeRepository.findWithLockByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
        Template template = ownedTemplate(store, templateId);
        Body body = readBody(template);
        YearMonth month = YearMonth.from(req.getPeriodStart());

        scheduleRepository.deleteAll(scheduleRepository.findByStoreIdAndTargetYearMonthAndStatus(
                store.getId(), month.toString(), Schedule.Status.DRAFT));
        scheduleRepository.flush();

        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setTargetYearMonth(month.toString());
        schedule.setStatus(Schedule.Status.DRAFT);
        schedule.setGenerationType(Schedule.GenerationType.TEMPLATE);
        schedule.setWeeklyHolidayPay(Schedule.WeeklyHolidayPay.ALLOW);
        schedule.setPriority(Schedule.Priority.FAIRNESS);
        schedule.setUpdatedAt(LocalDateTime.now());

        Outcome outcome = insert(store, schedule, monthPlacements(month, body));
        ScheduleService.recompute(schedule);

        StringBuilder comment = new StringBuilder("템플릿 \"" + template.getName() + "\"을 적용했습니다.");
        outcome.warnings().forEach(w -> comment.append(' ').append(w));
        schedule.setAiComment(comment.toString());
        return ScheduleDto.Response.from(scheduleRepository.save(schedule));
    }

    // ---------------------------------------------------------------- 적용 2: 기존 근무표의 한 주

    /** 대상 주(월~일, 근무표의 달 안의 날짜만)의 기존 근무·연차를 비우고 템플릿을 삽입한다. */
    @Transactional
    public ApplyWeekResult applyToWeek(Long userId, Long scheduleId, TemplateDto.ApplyWeekRequest req) {
        Store store = myStore(userId);
        Schedule schedule = scheduleRepository.findByIdAndStoreId(scheduleId, store.getId())
                .orElseThrow(() -> ApiException.notFound("근무표를 찾을 수 없습니다."));
        ScheduleService.requireDraft(schedule);
        Template template = ownedTemplate(store, req.getTemplateId());
        if (req.getWeekStart().getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.badRequest("주 시작일은 월요일이어야 합니다.");
        }
        YearMonth month = YearMonth.parse(schedule.getTargetYearMonth());
        LocalDate weekStart = req.getWeekStart();
        LocalDate weekEnd = weekStart.plusDays(6);
        if (weekEnd.isBefore(month.atDay(1)) || weekStart.isAfter(month.atEndOfMonth())) {
            throw ApiException.badRequest("선택한 주가 이 근무표의 달에 속하지 않습니다.");
        }

        List<Placement> placements = weekPlacements(weekStart, month, readBody(template));
        schedule.getShifts().removeIf(s -> !s.getWorkDate().isBefore(weekStart) && !s.getWorkDate().isAfter(weekEnd));
        Outcome outcome = insert(store, schedule, placements);
        schedule.setUpdatedAt(LocalDateTime.now());
        ScheduleService.recompute(schedule);
        return new ApplyWeekResult(ScheduleDto.Response.from(schedule), outcome.warnings());
    }

    // ---------------------------------------------------------------- 삽입 공통

    private record Outcome(int inserted, int missingStaff, int ruleSkipped, int availabilityWarnings) {
        List<String> warnings() {
            List<String> w = new ArrayList<>();
            if (missingStaff > 0) w.add("삭제된 구성원의 근무 " + missingStaff + "건은 제외했습니다.");
            if (ruleSkipped > 0) w.add("근로 규칙(겹침·연소자 제한 등)에 맞지 않아 " + ruleSkipped + "건은 제외했습니다.");
            if (availabilityWarnings > 0) w.add("근무 가능 파트와 맞지 않는 근무가 " + availabilityWarnings + "건 있으니 확인해 주세요.");
            return w;
        }
    }

    private Outcome insert(Store store, Schedule schedule, List<Placement> placements) {
        Map<Long, Worker> workers = workerRepository.findByStoreIdOrderById(store.getId()).stream()
                .collect(Collectors.toMap(Worker::getId, w -> w));
        Map<Long, Part> parts = partRepository.findByStoreIdOrderByStartTime(store.getId()).stream()
                .collect(Collectors.toMap(Part::getId, p -> p));
        Set<String> allowed = new HashSet<>();
        for (WorkerAvailability a : availabilityRepository.findByWorkerIdIn(workers.keySet())) {
            if (a.getPart() != null) allowed.add(a.getWorker().getId() + "|" + a.getDayOfWeek() + "|" + a.getPart().getId());
        }

        List<WorkBlock> accepted = schedule.getShifts().stream().map(WorkBlock::of)
                .collect(Collectors.toCollection(ArrayList::new));
        int inserted = 0, missing = 0, skipped = 0, availability = 0;

        // 연차를 먼저 넣는다: 같은 사람·날짜의 근무는 연차에 밀려 들어가지 않는다.
        Set<String> leaveKeys = new HashSet<>();
        for (Placement p : placements) {
            if (p.leave() == null) continue;
            Worker w = workers.get(p.leave().staffId());
            if (w == null) {
                missing++;
                continue;
            }
            Shift leave = ScheduleService.normalizeLeave(LeaveBody.builder().staffId(w.getId())
                    .date(p.date().toString()).type("ANNUAL").build(), w);
            schedule.getShifts().removeIf(s -> s.getWorker().getId().equals(w.getId()) && s.getWorkDate().equals(p.date()));
            accepted.removeIf(b -> b.workerId().equals(w.getId()) && b.date().equals(p.date()));
            schedule.addShift(leave);
            accepted.add(WorkBlock.of(leave));
            leaveKeys.add(w.getId() + "|" + p.date());
            inserted++;
        }

        for (Placement p : placements) {
            ShiftItem item = p.shift();
            if (item == null) continue;
            Worker w = workers.get(item.staffId());
            if (w == null) {
                missing++;
                continue;
            }
            if (leaveKeys.contains(w.getId() + "|" + p.date())) continue;

            Part part = null;
            if (item.partId() != null && !item.partId().isBlank()) {
                try {
                    part = parts.get(Long.valueOf(item.partId()));
                } catch (NumberFormatException ignored) {
                    // 알 수 없는 파트 id 는 파트 없음으로 본다
                }
            }
            try {
                ShiftBody body = ShiftBody.builder().staffId(w.getId()).date(p.date().toString())
                        .start(item.start()).end(item.end()).partId(part == null ? null : String.valueOf(part.getId()))
                        .breakStart(item.breakStart()).breakMinutes(item.breakMinutes()).tentative(item.tentative()).build();
                Shift shift = ScheduleService.normalize(body, w, part);
                List<WorkBlock> trial = new ArrayList<>(accepted);
                trial.add(WorkBlock.of(shift));
                ShiftValidator.validate(trial);

                if (part != null && !allowed.contains(w.getId() + "|" + Weekday.of(p.date().getDayOfWeek()) + "|" + part.getId())) {
                    availability++;
                }
                accepted = trial;
                schedule.addShift(shift);
                inserted++;
            } catch (ApiException e) {
                skipped++;
            }
        }
        return new Outcome(inserted, missing, skipped, availability);
    }

    // ---------------------------------------------------------------- 순수 변환 (테스트 대상)

    /** 날짜 하나에 놓일 템플릿 항목 하나. shift 와 leave 중 하나만 채워진다. */
    record Placement(LocalDate date, ShiftItem shift, LeaveItem leave) {}

    /** 그 달 모든 날짜에 같은 dayIndex 의 근무를 복사하고, 연차는 그 요일의 첫 날에만 둔다. */
    static List<Placement> monthPlacements(YearMonth month, Body body) {
        List<Placement> out = new ArrayList<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            int idx = dayIndex(d);
            for (ShiftItem s : nullSafe(body.shifts())) {
                if (s.dayIndex() == idx) out.add(new Placement(d, s, null));
            }
        }
        for (LeaveItem l : nullSafe(body.leaves())) {
            for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
                if (dayIndex(d) == l.dayIndex()) {
                    out.add(new Placement(d, null, l));
                    break;
                }
            }
        }
        return out;
    }

    /** 월요일 weekStart 로 시작하는 한 주에, 그 달 안의 날짜에 해당하는 항목만 놓는다. */
    static List<Placement> weekPlacements(LocalDate weekStart, YearMonth month, Body body) {
        List<Placement> out = new ArrayList<>();
        for (ShiftItem s : nullSafe(body.shifts())) {
            LocalDate d = weekStart.plusDays(s.dayIndex());
            if (YearMonth.from(d).equals(month)) out.add(new Placement(d, s, null));
        }
        for (LeaveItem l : nullSafe(body.leaves())) {
            LocalDate d = weekStart.plusDays(l.dayIndex());
            if (YearMonth.from(d).equals(month)) out.add(new Placement(d, null, l));
        }
        return out;
    }

    /** 근무표의 한 주를 날짜 -> dayIndex 로 바꿔 템플릿 본문으로 만든다. */
    static Body toBody(Collection<Shift> shifts, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        List<Shift> inWeek = shifts.stream()
                .filter(s -> !s.getWorkDate().isBefore(weekStart) && !s.getWorkDate().isAfter(weekEnd))
                .sorted(Comparator.comparing(Shift::getWorkDate)
                        .thenComparing(Shift::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<ShiftItem> items = new ArrayList<>();
        List<LeaveItem> leaves = new ArrayList<>();
        for (Shift s : inWeek) {
            int idx = dayIndex(s.getWorkDate());
            if (s.getStatus() == Shift.Status.ANNUAL_LEAVE) {
                leaves.add(new LeaveItem(idx, s.getWorker().getId(), "ANNUAL"));
            } else if (s.getStatus() == Shift.Status.NORMAL || s.getStatus() == Shift.Status.TENTATIVE) {
                items.add(new ShiftItem(idx, s.getWorker().getId(),
                        TimeText.format(s.getStartTime()), TimeText.formatEnd(s.getStartTime(), s.getEndTime()),
                        s.getPart() == null ? null : String.valueOf(s.getPart().getId()),
                        TimeText.format(s.getBreakStartTime()),
                        s.getBreakMinutes() == null ? 0 : s.getBreakMinutes(),
                        s.getStatus() == Shift.Status.TENTATIVE));
            }
        }
        return new Body(items, leaves);
    }

    static LocalDate firstFullWeek(YearMonth month) {
        LocalDate d = month.atDay(1);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) d = d.plusDays(1);
        return isFullWeekIn(d, month) ? d : null;
    }

    static boolean isFullWeekIn(LocalDate monday, YearMonth month) {
        return YearMonth.from(monday).equals(month) && YearMonth.from(monday.plusDays(6)).equals(month);
    }

    static int dayIndex(LocalDate d) {
        return d.getDayOfWeek().getValue() - 1;
    }

    private static <T> List<T> nullSafe(List<T> l) {
        return l == null ? List.of() : l;
    }

    // ---------------------------------------------------------------- 내부 유틸

    private Store myStore(Long userId) {
        return storeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
    }

    private Template ownedTemplate(Store store, Long id) {
        return templateRepository.findByIdAndStoreId(id, store.getId())
                .orElseThrow(() -> ApiException.notFound("템플릿을 찾을 수 없습니다."));
    }

    private Body readBody(Template t) {
        try {
            Body b = mapper.readValue(t.getShiftsJson(), Body.class);
            return b == null ? new Body(List.of(), List.of()) : b;
        } catch (RuntimeException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "템플릿 데이터를 읽을 수 없습니다.");
        }
    }

    private TemplateDto.Response toResponse(Template t) {
        Body b = readBody(t);
        return TemplateDto.Response.builder().id(t.getId()).name(t.getName())
                .createdAt(t.getCreatedAt() == null ? null : t.getCreatedAt().toString())
                .shifts(nullSafe(b.shifts())).leaves(nullSafe(b.leaves())).build();
    }
}