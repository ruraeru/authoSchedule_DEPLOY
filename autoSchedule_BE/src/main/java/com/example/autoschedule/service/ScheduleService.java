package com.example.autoschedule.service;

import com.example.autoschedule.dto.ScheduleDto;
import com.example.autoschedule.dto.ScheduleDto.LeaveBody;
import com.example.autoschedule.dto.ScheduleDto.ShiftBody;
import com.example.autoschedule.dto.TimeText;
import com.example.autoschedule.entity.*;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 근무표 업무 로직.
 * <ul>
 *   <li>직원(FULL_TIME): 파트 없이 15분 단위 출퇴근 시간으로 지정. 시간 미정(tentative) 허용.</li>
 *   <li>알바생(PART_TIME): 파트 id 로 지정하며 시각은 파트 시각으로 덮어쓴다.</li>
 *   <li>모든 저장 경로에서 휴게시간을 계산하고 {@link ShiftValidator} 하드 제약을 통과해야 한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final StoreRepository storeRepository;
    private final ScheduleRepository scheduleRepository;
    private final ShiftRepository shiftRepository;
    private final WorkerRepository workerRepository;
    private final PartRepository partRepository;
    private final WorkerAvailabilityRepository availabilityRepository;

    // ---------------------------------------------------------------- 조회

    @Transactional(readOnly = true)
    public List<ScheduleDto.Response> list(Long userId) {
        // 매장이 아직 없으면(가입 직후 매장 조회와 동시에 불릴 때) 빈 목록
        return storeRepository.findByUserId(userId)
                .map(store -> scheduleRepository.findByStoreIdOrderByTargetYearMonthDesc(store.getId()).stream()
                        .map(ScheduleDto.Response::from).toList())
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public ScheduleDto.Response get(Long userId, Long scheduleId) {
        return ScheduleDto.Response.from(ownedSchedule(userId, scheduleId));
    }

    // ---------------------------------------------------------------- 생성 / 확정

    /** 직접 만들기. 같은 달 DRAFT 는 지우고 새로 만든다(CONFIRMED 는 유지). */
    @Transactional
    public ScheduleDto.Response create(Long userId, ScheduleDto.CreateRequest req) {
        Store store = storeRepository.findWithLockByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
        YearMonth ym = YearMonth.from(req.getPeriodStart());
        discardDrafts(store.getId(), ym);

        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setTargetYearMonth(ym.toString());
        schedule.setStatus(Schedule.Status.DRAFT);
        schedule.setGenerationType(Schedule.GenerationType.MANUAL);
        schedule.setWeeklyHolidayPay(Schedule.WeeklyHolidayPay.ALLOW);
        schedule.setPriority(Schedule.Priority.FAIRNESS);
        schedule.setAiComment("");
        schedule.setUpdatedAt(LocalDateTime.now());

        if (req.isPrefillFixed()) prefillFixedStaff(store, schedule, ym);
        recompute(schedule);
        return ScheduleDto.Response.from(scheduleRepository.save(schedule));
    }

    /** 확정본도 지울 수 있다. 근무·연차는 cascade 로 함께 지워진다. */
    @Transactional
    public void delete(Long userId, Long scheduleId) {
        scheduleRepository.delete(ownedSchedule(userId, scheduleId));
    }

    @Transactional
    public ScheduleDto.Response confirm(Long userId, Long scheduleId) {
        Schedule schedule = ownedSchedule(userId, scheduleId);
        schedule.setStatus(Schedule.Status.CONFIRMED);
        schedule.setUpdatedAt(LocalDateTime.now());
        return ScheduleDto.Response.from(schedule);
    }

    // ---------------------------------------------------------------- 에디터 저장 (통째 교체)

    /**
     * PUT /schedules/{id}/shifts. shifts·leaves 를 통째로 교체한다.
     * 연차가 있는 날의 같은 사람 근무는 버린다(연차 등록 시 기존 근무 삭제 규칙).
     * 같은 id 를 삭제+삽입하면 flush 순서(insert 먼저)로 PK 충돌이 나므로, id 가 같은 행은 제자리 갱신한다.
     */
    @Transactional
    public ScheduleDto.Response saveAll(Long userId, Long scheduleId, ScheduleDto.SaveShiftsRequest req) {
        Schedule schedule = editableSchedule(userId, scheduleId);
        Lookup lookup = lookup(schedule.getStore().getId());

        List<Shift> incoming = new ArrayList<>();
        Set<String> leaveKeys = new HashSet<>();
        for (LeaveBody l : nullSafe(req.getLeaves())) {
            Shift leave = normalizeLeave(l, lookup.worker(l.getStaffId()));
            leaveKeys.add(leave.getWorker().getId() + "|" + leave.getWorkDate());
            incoming.add(leave);
        }
        for (ShiftBody b : nullSafe(req.getShifts())) {
            Worker worker = lookup.worker(b.getStaffId());
            Shift s = normalize(b, worker, lookup.part(b.getPartId()));
            if (!leaveKeys.contains(worker.getId() + "|" + s.getWorkDate())) incoming.add(s);
        }

        Set<String> seen = new HashSet<>();
        Map<String, Shift> existing = schedule.getShifts().stream()
                .collect(Collectors.toMap(Shift::getId, s -> s));
        for (Shift s : incoming) {
            if (!seen.add(s.getId())) throw ApiException.badRequest("중복된 근무 ID가 있습니다.");
            if (!existing.containsKey(s.getId()) && shiftRepository.existsById(s.getId())) {
                throw ApiException.badRequest("이미 사용 중인 근무 ID입니다.");
            }
        }

        ShiftValidator.validate(incoming.stream().map(WorkBlock::of).toList());

        schedule.getShifts().removeIf(s -> !seen.contains(s.getId()));
        for (Shift s : incoming) {
            Shift managed = existing.get(s.getId());
            if (managed == null) schedule.addShift(s);
            else copyInto(managed, s);
        }
        touch(schedule);
        return ScheduleDto.Response.from(schedule);
    }

    // ---------------------------------------------------------------- 개별 근무 블록

    @Transactional
    public ShiftBody addShift(Long userId, Long scheduleId, ShiftBody body) {
        Schedule schedule = editableSchedule(userId, scheduleId);
        Lookup lookup = lookup(schedule.getStore().getId());
        Shift s = normalize(body, lookup.worker(body.getStaffId()), lookup.part(body.getPartId()));
        if (shiftRepository.existsById(s.getId())) throw ApiException.badRequest("이미 사용 중인 근무 ID입니다.");

        validateWith(schedule, null, s);
        schedule.addShift(s);
        touch(schedule);
        return ShiftBody.from(s);
    }

    @Transactional
    public ShiftBody updateShift(Long userId, Long scheduleId, String shiftId, ShiftBody body) {
        Schedule schedule = editableSchedule(userId, scheduleId);
        Shift target = findShift(schedule, shiftId);
        Lookup lookup = lookup(schedule.getStore().getId());
        body.setId(shiftId);
        Shift candidate = normalize(body, lookup.worker(body.getStaffId()), lookup.part(body.getPartId()));

        validateWith(schedule, shiftId, candidate);
        copyInto(target, candidate);
        touch(schedule);
        return ShiftBody.from(target);
    }

    /** 근무든 연차든 id 로 삭제한다(연차도 shifts 행). */
    @Transactional
    public void deleteShift(Long userId, Long scheduleId, String shiftId) {
        Schedule schedule = editableSchedule(userId, scheduleId);
        Shift target = findShift(schedule, shiftId);
        schedule.getShifts().remove(target);
        touch(schedule);
    }

    /** 연차 등록: 그 날짜 같은 사람의 기존 근무를 삭제하고 연차(유급 8h)를 넣는다. */
    @Transactional
    public LeaveBody addLeave(Long userId, Long scheduleId, LeaveBody body) {
        Schedule schedule = editableSchedule(userId, scheduleId);
        Lookup lookup = lookup(schedule.getStore().getId());
        Shift leave = normalizeLeave(body, lookup.worker(body.getStaffId()));
        if (shiftRepository.existsById(leave.getId())) throw ApiException.badRequest("이미 사용 중인 근무 ID입니다.");

        schedule.getShifts().removeIf(s -> s.getWorker().getId().equals(leave.getWorker().getId())
                && s.getWorkDate().equals(leave.getWorkDate()));
        schedule.addShift(leave);
        touch(schedule);
        return LeaveBody.from(leave);
    }

    // ---------------------------------------------------------------- 정규화 (고용 형태별 + 휴게 계산)

    /** 요청 한 건을 저장 가능한 Shift(비영속)로 만든다. 고용 형태 규칙·휴게 계산·값 검증 포함. */
    static Shift normalize(ShiftBody b, Worker worker, Part part) {
        LocalDate date = parseDate(b.getDate());
        LocalTime start;
        LocalTime end;
        boolean tentative = b.isTentative();
        try {
            start = TimeText.parse(b.getStart());
            end = TimeText.parse(b.getEnd());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("시간 형식이 올바르지 않습니다. (HH:MM)");
        }

        if (worker.isFullTime()) {
            if (part != null) throw ApiException.badRequest(worker.getName() + "님은 직원이라 파트가 아니라 출퇴근 시간으로 지정해야 합니다.");
            if (start == null && end == null) {
                if (!tentative) throw ApiException.badRequest(worker.getName() + "님의 출근·퇴근 시각을 입력하세요.");
            } else {
                requireQuarterHour(start, worker);
                requireQuarterHour(end, worker);
            }
        } else {
            if (tentative) throw ApiException.badRequest(worker.getName() + "님은 알바생이라 근무 시간 미정을 쓸 수 없습니다.");
            if (part != null) {
                start = part.getStartTime();
                end = part.getEndTime();
            } else if (start == null || end == null) {
                throw ApiException.badRequest(worker.getName() + "님은 알바생이라 파트를 지정해야 합니다.");
            } else {
                // 파트가 삭제돼 partId 가 null 이 된 기존 근무는 시각이 있을 때만 유지한다.
                requireQuarterHour(start, worker);
                requireQuarterHour(end, worker);
            }
        }
        if ((start == null) != (end == null)) {
            throw ApiException.badRequest("출근 시각과 퇴근 시각은 함께 입력해야 합니다.");
        }

        Shift s = new Shift();
        s.setId(blankToNull(b.getId()) == null ? UUID.randomUUID().toString() : b.getId());
        s.setWorker(worker);
        s.setPart(part);
        s.setWorkDate(date);
        s.setStatus(tentative ? Shift.Status.TENTATIVE : Shift.Status.NORMAL);

        if (start == null) {
            s.setBreakMinutes(0);
            return s;
        }
        int startMin = Minutes.ofStart(start);
        int endMin = Minutes.ofEnd(start, end);
        int span = endMin - startMin;
        if (span >= Minutes.DAY) throw ApiException.badRequest("퇴근 시각은 출근 시각과 달라야 합니다.");
        if (b.getBreakMinutes() < 0) throw ApiException.badRequest("휴게시간은 0분 이상이어야 합니다.");

        int breakMin = BreakCalculator.resolve(span, b.getBreakMinutes(), part == null ? null : part.getBreakMinutes());
        if (breakMin >= span) throw ApiException.badRequest("휴게시간이 근무 시간보다 길 수 없습니다.");

        Integer preferred;
        try {
            LocalTime bs = TimeText.parse(b.getBreakStart());
            preferred = bs == null ? null : Minutes.ofStart(bs);
            if (preferred != null && preferred < startMin) preferred += Minutes.DAY; // 자정 넘은 휴게
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("휴게 시작 시각 형식이 올바르지 않습니다. (HH:MM)");
        }
        Integer breakStart = BreakCalculator.placeBreakStart(startMin, endMin, breakMin, preferred);

        s.setStartTime(start);
        s.setEndTime(end);
        s.setBreakMinutes(breakMin);
        s.setBreakStartTime(breakStart == null ? null : Minutes.toTime(breakStart));
        return s;
    }

    static Shift normalizeLeave(LeaveBody b, Worker worker) {
        Shift s = new Shift();
        s.setId(blankToNull(b.getId()) == null ? UUID.randomUUID().toString() : b.getId());
        s.setWorker(worker);
        s.setWorkDate(parseDate(b.getDate()));
        s.setBreakMinutes(0);
        s.setStatus(Shift.Status.ANNUAL_LEAVE);
        return s;
    }

    private static void requireQuarterHour(LocalTime t, Worker worker) {
        if (t != null && (t.getMinute() % 15 != 0 || t.getSecond() != 0)) {
            throw ApiException.badRequest(worker.getName() + "님의 출퇴근 시각은 15분 단위로 입력하세요.");
        }
    }

    // ---------------------------------------------------------------- 직접 만들기: 고정 근무(직원 시각, 알바생 파트) 미리 채우기

    private void prefillFixedStaff(Store store, Schedule schedule, YearMonth ym) {
        List<Worker> staff = workerRepository.findByStoreIdOrderById(store.getId());
        if (staff.isEmpty()) return;

        // 직원: 요일별 고정 출퇴근 시각, 알바생: 요일별 고정 근무 파트 (프론트 fixedShifts 와 같음)
        Map<Long, Map<Weekday, WorkerAvailability>> fixed = new HashMap<>();
        Map<Long, Map<Weekday, List<Part>>> fixedParts = new HashMap<>();
        for (WorkerAvailability a : availabilityRepository
                .findByWorkerIdIn(staff.stream().map(Worker::getId).toList())) {
            if (a.getWorker().isFullTime() && a.getStartTime() != null && a.getEndTime() != null) {
                fixed.computeIfAbsent(a.getWorker().getId(), k -> new EnumMap<>(Weekday.class))
                        .put(a.getDayOfWeek(), a);
            } else if (!a.getWorker().isFullTime() && a.getPart() != null) {
                fixedParts.computeIfAbsent(a.getWorker().getId(), k -> new EnumMap<>(Weekday.class))
                        .computeIfAbsent(a.getDayOfWeek(), k -> new ArrayList<>()).add(a.getPart());
            }
        }
        Map<Weekday, StoreHours> hours = new EnumMap<>(Weekday.class);
        store.getHours().forEach(h -> hours.put(h.getDayOfWeek(), h));

        List<WorkBlock> accepted = new ArrayList<>();
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            Weekday wd = Weekday.of(d.getDayOfWeek());
            StoreHours open = hours.get(wd);
            for (Worker w : staff) {
                if (!w.isFullTime()) {
                    for (Part part : fixedParts.getOrDefault(w.getId(), Map.of()).getOrDefault(wd, List.of())) {
                        ShiftBody body = ShiftBody.builder().staffId(w.getId()).date(d.toString())
                                .partId(String.valueOf(part.getId())).build();
                        prefill(schedule, accepted, body, w, part);
                    }
                    continue;
                }
                WorkerAvailability a = fixed.getOrDefault(w.getId(), Map.of()).get(wd);
                if (a == null) continue;
                if (open != null && !withinHours(a, open)) continue; // 영업시간을 벗어나는 날은 제외

                ShiftBody body = ShiftBody.builder().staffId(w.getId()).date(d.toString())
                        .start(TimeText.format(a.getStartTime()))
                        .end(TimeText.formatEnd(a.getStartTime(), a.getEndTime())).build();
                prefill(schedule, accepted, body, w, null);
            }
        }
    }

    /** 규칙(겹침 등)에 맞을 때만 근무를 넣는다. 맞지 않는 고정 근무는 미리 채우지 않는다. */
    private static void prefill(Schedule schedule, List<WorkBlock> accepted, ShiftBody body, Worker w, Part part) {
        try {
            Shift s = normalize(body, w, part);
            List<WorkBlock> trial = new ArrayList<>(accepted);
            trial.add(WorkBlock.of(s));
            ShiftValidator.validate(trial);
            accepted.add(WorkBlock.of(s));
            schedule.addShift(s);
        } catch (ApiException skip) {
            // 건너뛴다
        }
    }

    private static boolean withinHours(WorkerAvailability a, StoreHours h) {
        int open = Minutes.ofStart(h.getOpenTime());
        int close = Minutes.ofEnd(h.getOpenTime(), h.getCloseTime());
        return Minutes.ofStart(a.getStartTime()) >= open
                && Minutes.ofEnd(a.getStartTime(), a.getEndTime()) <= close;
    }

    // ---------------------------------------------------------------- 통계

    /**
     * total_work_hours / expected_labor_cost / holiday_pay_target_count 재계산.
     * 인건비는 {@link LaborCostCalculator}(기본급 + 주휴수당 + 5인 이상 가산수당)와 같은 공식이다.
     * 이웃 달 근무는 모르므로 달에 걸친 주는 근사치이며, 정확한 값은 리포트(ReportService)가 계산한다.
     */
    public static void recompute(Schedule schedule) {
        Map<Long, LaborCostCalculator.WorkerInfo> workers = new LinkedHashMap<>();
        for (Shift s : schedule.getShifts()) {
            Worker w = s.getWorker();
            workers.putIfAbsent(w.getId(), new LaborCostCalculator.WorkerInfo(w.getId(), w.getName(), w.isFullTime(),
                    w.getHourlyWage()));
        }
        LaborCostCalculator.Result r = LaborCostCalculator.calculate(
                YearMonth.parse(schedule.getTargetYearMonth()),
                schedule.getStore().getScale() == Store.Scale.OVER_5,
                workers.values(), LaborCostCalculator.payBlocks(schedule.getShifts()));

        schedule.setTotalWorkHours((int) Math.round(r.workMinutes() / 60.0));
        schedule.setExpectedLaborCost((int) r.total());
        schedule.setHolidayPayTargetCount(r.holidayPayTargetCount());
    }
    private void touch(Schedule schedule) {
        recompute(schedule);
        schedule.setUpdatedAt(LocalDateTime.now());
    }

    // ---------------------------------------------------------------- 내부 유틸

    /** 대상 근무를 (옛 버전 제외하고) 현재 근무표 전체와 함께 검증한다. */
    private void validateWith(Schedule schedule, String replacingId, Shift candidate) {
        List<WorkBlock> all = schedule.getShifts().stream()
                .filter(s -> !s.getId().equals(replacingId))
                .map(WorkBlock::of).collect(Collectors.toCollection(ArrayList::new));
        all.add(WorkBlock.of(candidate));
        ShiftValidator.validate(all);
    }

    private static void copyInto(Shift target, Shift src) {
        target.setWorker(src.getWorker());
        target.setPart(src.getPart());
        target.setWorkDate(src.getWorkDate());
        target.setStartTime(src.getStartTime());
        target.setEndTime(src.getEndTime());
        target.setBreakMinutes(src.getBreakMinutes());
        target.setBreakStartTime(src.getBreakStartTime());
        target.setStatus(src.getStatus());
    }

    private void discardDrafts(Long storeId, YearMonth ym) {
        List<Schedule> drafts = scheduleRepository.findByStoreIdAndTargetYearMonthAndStatus(
                storeId, ym.toString(), Schedule.Status.DRAFT);
        scheduleRepository.deleteAll(drafts);
        scheduleRepository.flush();
    }

    private Store myStore(Long userId) {
        return storeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
    }

    private Schedule ownedSchedule(Long userId, Long scheduleId) {
        Store store = myStore(userId);
        return scheduleRepository.findByIdAndStoreId(scheduleId, store.getId())
                .orElseThrow(() -> ApiException.notFound("근무표를 찾을 수 없습니다."));
    }

    private Schedule editableSchedule(Long userId, Long scheduleId) {
        Schedule schedule = ownedSchedule(userId, scheduleId);
        requireDraft(schedule);
        return schedule;
    }

    /** 확정된 근무표는 수정할 수 없다. */
    static void requireDraft(Schedule schedule) {
        if (schedule.getStatus() == Schedule.Status.CONFIRMED) {
            throw ApiException.conflict("확정된 근무표는 수정할 수 없습니다.");
        }
    }

    private static Shift findShift(Schedule schedule, String shiftId) {
        return schedule.getShifts().stream().filter(s -> s.getId().equals(shiftId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("근무를 찾을 수 없습니다."));
    }

    private Lookup lookup(Long storeId) {
        Map<Long, Worker> workers = workerRepository.findByStoreIdOrderById(storeId).stream()
                .collect(Collectors.toMap(Worker::getId, w -> w));
        Map<Long, Part> parts = partRepository.findByStoreIdOrderByStartTime(storeId).stream()
                .collect(Collectors.toMap(Part::getId, p -> p));
        return new Lookup(workers, parts);
    }

    private record Lookup(Map<Long, Worker> workers, Map<Long, Part> parts) {
        Worker worker(Long id) {
            Worker w = id == null ? null : workers.get(id);
            if (w == null) throw ApiException.badRequest("존재하지 않는 구성원입니다.");
            return w;
        }

        /** partId 가 비어 있으면 null, 값이 있는데 이 매장 파트가 아니면 오류. */
        Part part(String partId) {
            if (blankToNull(partId) == null) return null;
            try {
                Part p = parts.get(Long.valueOf(partId));
                if (p != null) return p;
            } catch (NumberFormatException ignored) {
                // fall through
            }
            throw ApiException.badRequest("존재하지 않는 파트입니다.");
        }
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}