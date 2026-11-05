package com.example.autoschedule.service;

import com.example.autoschedule.entity.Shift;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 월간 인건비·주휴수당 계산 (DB 와 무관한 순수 계산).
 *
 * <ul>
 *   <li><b>기본급</b> = 시급 x 이 달 실근로시간(휴게 제외). 직원의 연차는 유급 8시간.</li>
 *   <li><b>주휴수당</b>: 월~일 한 주의 소정근로(직원 연차 포함)가 15시간 이상이면 발생.
 *       금액 = 시급 x min(주 근로시간, 40) / 40 x 8. 한 주는 <b>월요일이 속한 달</b>에 귀속시켜 달 사이에 중복·누락이 없게 한다.</li>
 *   <li><b>가산수당(5인 이상만)</b>: 연장(1일 8시간 초과, 또는 1주 40시간 초과 - 일 초과분 제외) 50%, 야간(22:00~06:00) 50%.
 *       연장과 야간이 겹치면 두 가산이 합산된다. 기본급(100%)은 이미 기본급에 들어 있으므로 가산분(0.5)만 더한다.</li>
 * </ul>
 * 달에 걸친 주는 호출 측이 이웃 달의 근무도 함께 넘겨야 정확하다. 기본급·가산수당은 "이 달 날짜"의 근무만 이 달로 귀속한다.
 */
public final class LaborCostCalculator {

    static final int DAILY_REGULAR = 8 * 60;
    static final int WEEKLY_REGULAR = 40 * 60;
    static final int HOLIDAY_PAY_MIN_WEEK = 15 * 60;
    static final int HOLIDAY_PAY_HOURS = 8;
    static final int LEAVE_MINUTES = 8 * 60;
    static final double PREMIUM_RATE = 0.5;
    private static final int NIGHT_END = 6 * 60;
    private static final int NIGHT_START = 22 * 60;

    private LaborCostCalculator() {}

    public record WorkerInfo(long id, String name, boolean fullTime, int hourlyWage) {}

    /** 근무 한 건. 연차면 leave=true(시각 없음). */
    public record PayBlock(long workerId, LocalDate date, Integer startMin, Integer endMin, int breakMinutes,
                           Integer breakStartMin, boolean leave) {}

    public record WeekLine(LocalDate weekStart, int workMinutes, boolean eligible, boolean countedInMonth) {}

    public record WorkerResult(WorkerInfo worker, int workMinutes, int leaveMinutes, int overtimeMinutes,
                               int nightMinutes, long basePay, long weeklyHolidayPay, long overtimePremium,
                               long nightPremium, List<WeekLine> weeks, int qualifyingWeeks) {
        public long total() {
            return basePay + weeklyHolidayPay + overtimePremium + nightPremium;
        }
    }

    public record Result(YearMonth month, boolean premiumsApplied, List<WorkerResult> workers, long basePay,
                         long weeklyHolidayPay, long overtimePremium, long nightPremium, int workMinutes,
                         int holidayPayTargetCount) {
        public long total() {
            return basePay + weeklyHolidayPay + overtimePremium + nightPremium;
        }
    }

    /** Shift 엔티티 -> 계산용 블록. 시간 미정·휴무는 제외한다. */
    public static List<PayBlock> payBlocks(Collection<Shift> shifts) {
        List<PayBlock> out = new ArrayList<>();
        for (Shift s : shifts) {
            long workerId = s.getWorker().getId();
            if (s.getStatus() == Shift.Status.ANNUAL_LEAVE) {
                out.add(new PayBlock(workerId, s.getWorkDate(), null, null, 0, null, true));
            } else if ((s.getStatus() == Shift.Status.NORMAL || s.getStatus() == Shift.Status.TENTATIVE)
                    && s.getStartTime() != null && s.getEndTime() != null) {
                int start = Minutes.ofStart(s.getStartTime());
                Integer breakStart = s.getBreakStartTime() == null ? null : Minutes.ofStart(s.getBreakStartTime());
                if (breakStart != null && breakStart < start) breakStart += Minutes.DAY; // 자정 넘은 휴게
                out.add(new PayBlock(workerId, s.getWorkDate(), start, Minutes.ofEnd(s.getStartTime(), s.getEndTime()),
                        s.getBreakMinutes() == null ? 0 : s.getBreakMinutes(), breakStart, false));
            }
        }
        return out;
    }

    public static Result calculate(YearMonth month, boolean premiumsApply, Collection<WorkerInfo> workers,
                                   Collection<PayBlock> blocks) {
        Map<Long, List<PayBlock>> byWorker = blocks.stream().collect(Collectors.groupingBy(PayBlock::workerId));
        List<WorkerResult> results = new ArrayList<>();
        for (WorkerInfo w : workers) {
            results.add(calculateWorker(month, premiumsApply, w, byWorker.getOrDefault(w.id(), List.of())));
        }
        long base = 0, holiday = 0, ot = 0, night = 0;
        int workMin = 0, targets = 0;
        for (WorkerResult r : results) {
            base += r.basePay();
            holiday += r.weeklyHolidayPay();
            ot += r.overtimePremium();
            night += r.nightPremium();
            workMin += r.workMinutes();
            if (!r.worker().fullTime() && r.qualifyingWeeks() > 0) targets++;
        }
        return new Result(month, premiumsApply, results, base, holiday, ot, night, workMin, targets);
    }

    private static WorkerResult calculateWorker(YearMonth month, boolean premiumsApply, WorkerInfo w,
                                                List<PayBlock> blocks) {
        Map<LocalDate, List<PayBlock>> byDay = blocks.stream()
                .collect(Collectors.groupingBy(PayBlock::date, TreeMap::new, Collectors.toList()));

        Map<LocalDate, Integer> weekMinutes = new TreeMap<>();
        int workInMonth = 0;
        int leaveInMonth = 0;
        for (Map.Entry<LocalDate, List<PayBlock>> e : byDay.entrySet()) {
            LocalDate date = e.getKey();
            LocalDate monday = date.with(DayOfWeek.MONDAY);
            boolean inMonth = YearMonth.from(date).equals(month);
            for (PayBlock b : e.getValue()) {
                if (b.leave()) {
                    if (!w.fullTime()) continue; // 유급 연차는 직원만
                    weekMinutes.merge(monday, LEAVE_MINUTES, Integer::sum);
                    if (inMonth) leaveInMonth += LEAVE_MINUTES;
                } else {
                    int net = netMinutes(b);
                    weekMinutes.merge(monday, net, Integer::sum);
                    if (inMonth) workInMonth += net;
                }
            }
        }

        // 주휴수당: 월요일이 이 달에 속하는 주만 이 달에 귀속
        List<WeekLine> weeks = new ArrayList<>();
        double holidayPay = 0;
        int qualifying = 0;
        for (Map.Entry<LocalDate, Integer> e : weekMinutes.entrySet()) {
            boolean counted = YearMonth.from(e.getKey()).equals(month);
            boolean eligible = e.getValue() >= HOLIDAY_PAY_MIN_WEEK;
            if (counted && eligible) {
                qualifying++;
                holidayPay += w.hourlyWage() * (Math.min(e.getValue(), WEEKLY_REGULAR) / (double) WEEKLY_REGULAR)
                        * HOLIDAY_PAY_HOURS;
            }
            weeks.add(new WeekLine(e.getKey(), e.getValue(), eligible, counted));
        }

        int overtime = 0;
        int nightMin = 0;
        if (premiumsApply) {
            int[] otNight = premiumMinutes(month, byDay);
            overtime = otNight[0];
            nightMin = otNight[1];
        }

        long basePay = Math.round(w.hourlyWage() * (workInMonth + leaveInMonth) / 60.0);
        return new WorkerResult(w, workInMonth, leaveInMonth, overtime, nightMin, basePay, Math.round(holidayPay),
                Math.round(w.hourlyWage() * PREMIUM_RATE * overtime / 60.0),
                Math.round(w.hourlyWage() * PREMIUM_RATE * nightMin / 60.0), weeks, qualifying);
    }

    /**
     * 시간순으로 근무 구간을 훑으며 정규/연장을 가른다. 이 달 날짜의 [연장 분, 야간 분] 을 돌려준다.
     * 연장 = 그 날 누적 8시간 초과 또는 그 주 정규근로 누적 40시간 초과.
     */
    private static int[] premiumMinutes(YearMonth month, Map<LocalDate, List<PayBlock>> byDay) {
        Map<LocalDate, List<LocalDate>> daysByWeek = byDay.keySet().stream()
                .collect(Collectors.groupingBy(d -> d.with(DayOfWeek.MONDAY), TreeMap::new, Collectors.toList()));
        int ot = 0;
        int night = 0;
        for (List<LocalDate> days : daysByWeek.values()) {
            Collections.sort(days);
            int weekRegular = 0;
            for (LocalDate date : days) {
                boolean inMonth = YearMonth.from(date).equals(month);
                List<int[]> intervals = new ArrayList<>();
                for (PayBlock b : byDay.get(date)) {
                    if (!b.leave()) intervals.addAll(workIntervals(b));
                }
                intervals.sort(Comparator.comparingInt(a -> a[0]));
                int dayCum = 0;
                for (int[] iv : intervals) {
                    int len = iv[1] - iv[0];
                    int regular = Math.min(len, Math.min(Math.max(0, DAILY_REGULAR - dayCum),
                            Math.max(0, WEEKLY_REGULAR - weekRegular)));
                    dayCum += len;
                    weekRegular += regular;
                    if (inMonth) {
                        ot += len - regular;
                        night += nightOverlap(iv[0], iv[1]);
                    }
                }
            }
        }
        return new int[]{ot, night};
    }

    /** 휴게를 뺀 실근로 구간. 휴게 시작이 없으면 서버의 기본 배치 규칙으로 놓는다. */
    static List<int[]> workIntervals(PayBlock b) {
        int s = b.startMin();
        int e = b.endMin();
        if (b.breakMinutes() <= 0) return List.of(new int[]{s, e});
        Integer bsOpt = b.breakStartMin() != null ? b.breakStartMin()
                : BreakCalculator.placeBreakStart(s, e, b.breakMinutes(), null);
        int bs = Math.max(s, Math.min(bsOpt, e));
        int be = Math.min(e, bs + b.breakMinutes());
        List<int[]> out = new ArrayList<>();
        if (bs > s) out.add(new int[]{s, bs});
        if (e > be) out.add(new int[]{be, e});
        return out;
    }

    static int netMinutes(PayBlock b) {
        return Math.max(0, b.endMin() - b.startMin() - b.breakMinutes());
    }

    /** 자정을 넘는 근무(b > 1440)는 다음 날 새벽 야간 구간까지 센다. */
    private static int nightOverlap(int a, int b) {
        int sum = 0;
        for (int day = 0; day < b; day += Minutes.DAY) {
            sum += overlap(a, b, day, day + NIGHT_END) + overlap(a, b, day + NIGHT_START, day + Minutes.DAY);
        }
        return sum;
    }

    private static int overlap(int a, int b, int x, int y) {
        return Math.max(0, Math.min(b, y) - Math.max(a, x));
    }
}