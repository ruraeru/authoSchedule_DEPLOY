package com.example.autoschedule.service;

import com.example.autoschedule.dto.ScheduleReportDto;
import com.example.autoschedule.dto.ScheduleReportDto.*;
import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Shift;
import com.example.autoschedule.entity.Store;
import com.example.autoschedule.entity.Worker;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.repository.ScheduleRepository;
import com.example.autoschedule.repository.StoreRepository;
import com.example.autoschedule.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 대시보드 리포트: 주휴수당 대상자 수와 예상 인건비를 서버에서 계산한다(PRD F4: LLM 이 아니라 서버가 수치를 계산).
 * 달에 걸친 주(예: 11/30(월)~12/6(일))는 이웃 달 근무표의 근무도 가져와 주 단위로 정확히 판정한다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final StoreRepository storeRepository;
    private final ScheduleRepository scheduleRepository;
    private final WorkerRepository workerRepository;

    @Transactional(readOnly = true)
    public ScheduleReportDto.Response report(Long userId, Long scheduleId) {
        Store store = storeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("매장 정보를 찾을 수 없습니다."));
        Schedule schedule = scheduleRepository.findByIdAndStoreId(scheduleId, store.getId())
                .orElseThrow(() -> ApiException.notFound("근무표를 찾을 수 없습니다."));

        YearMonth month = YearMonth.parse(schedule.getTargetYearMonth());
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate rangeStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate rangeEnd = last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

        List<Shift> shifts = new ArrayList<>(schedule.getShifts());
        List<String> warnings = new ArrayList<>();
        if (rangeStart.isBefore(first)) {
            addNeighbor(store, month.minusMonths(1), d -> !d.isBefore(rangeStart), shifts, warnings, "이전 달");
        }
        if (rangeEnd.isAfter(last)) {
            addNeighbor(store, month.plusMonths(1), d -> !d.isAfter(rangeEnd), shifts, warnings, "다음 달");
        }

        List<Worker> workers = workerRepository.findByStoreIdOrderById(store.getId());
        List<LaborCostCalculator.WorkerInfo> infos = workers.stream()
                .map(w -> new LaborCostCalculator.WorkerInfo(w.getId(), w.getName(), w.isFullTime(), w.getHourlyWage()))
                .toList();

        boolean premiums = store.getScale() == Store.Scale.OVER_5;
        LaborCostCalculator.Result r = LaborCostCalculator.calculate(month, premiums, infos,
                LaborCostCalculator.payBlocks(shifts));

        Map<Long, Worker> byId = new HashMap<>();
        workers.forEach(w -> byId.put(w.getId(), w));
        List<WorkerRow> rows = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        for (LaborCostCalculator.WorkerResult wr : r.workers()) {
            if (wr.workMinutes() == 0 && wr.leaveMinutes() == 0 && wr.weeks().isEmpty()) continue; // 이번 달 배치 없음
            Worker w = byId.get(wr.worker().id());
            rows.add(WorkerRow.builder()
                    .workerId(w.getId()).name(w.getName()).employmentType(w.getEmploymentType())
                    .workHours(hours(wr.workMinutes())).leaveHours(hours(wr.leaveMinutes()))
                    .overtimeHours(hours(wr.overtimeMinutes())).nightHours(hours(wr.nightMinutes()))
                    .basePay(wr.basePay()).weeklyHolidayPay(wr.weeklyHolidayPay())
                    .overtimePremium(wr.overtimePremium()).nightPremium(wr.nightPremium()).totalPay(wr.total())
                    .weeks(wr.weeks().stream().map(l -> WeekRow.builder()
                            .weekStart(l.weekStart().toString()).workHours(hours(l.workMinutes()))
                            .holidayPayEligible(l.eligible()).countedInThisMonth(l.countedInMonth()).build()).toList())
                    .build());
            if (!w.isFullTime() && wr.qualifyingWeeks() > 0) {
                targets.add(Target.builder().workerId(w.getId()).name(w.getName())
                        .qualifyingWeeks(wr.qualifyingWeeks()).build());
            }
        }

        return ScheduleReportDto.Response.builder()
                .scheduleId(schedule.getId())
                .month(schedule.getTargetYearMonth())
                .status(schedule.getStatus())
                .premiumApplied(premiums)
                .summary(Summary.builder()
                        .totalWorkHours(hours(r.workMinutes()))
                        .basePay(r.basePay()).weeklyHolidayPay(r.weeklyHolidayPay())
                        .overtimePremium(r.overtimePremium()).nightPremium(r.nightPremium())
                        .totalLaborCost(r.total())
                        .holidayPayTargetCount(r.holidayPayTargetCount()).holidayPayTargets(targets).build())
                .workers(rows)
                .warnings(warnings)
                .build();
    }

    /** 이웃 달은 확정본이 있으면 그것, 없으면 가장 최근 작성 중 근무표의 근무를 쓴다. */
    private void addNeighbor(Store store, YearMonth neighbor, java.util.function.Predicate<LocalDate> inRange,
                             List<Shift> into, List<String> warnings, String label) {
        List<Schedule> candidates = scheduleRepository.findByStoreIdAndTargetYearMonth(store.getId(), neighbor.toString());
        Optional<Schedule> chosen = candidates.stream().filter(s -> s.getStatus() == Schedule.Status.CONFIRMED)
                .max(Comparator.comparing(Schedule::getId));
        if (chosen.isEmpty()) chosen = candidates.stream().max(Comparator.comparing(Schedule::getId));
        if (chosen.isEmpty()) {
            warnings.add(label + " 근무표가 없어 달에 걸친 주는 이 달 근무만으로 계산했습니다.");
            return;
        }
        chosen.get().getShifts().stream().filter(s -> inRange.test(s.getWorkDate())).forEach(into::add);
    }

    private static double hours(int minutes) {
        return Math.round(minutes / 60.0 * 100) / 100.0;
    }
}