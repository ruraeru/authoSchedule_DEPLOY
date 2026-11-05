package com.example.autoschedule.dto;

import com.example.autoschedule.entity.Part;
import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Shift;
import com.example.autoschedule.entity.Worker;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** api-spec 6장 Schedule. 연차(leaves)는 DB 에서 status=ANNUAL_LEAVE 인 shifts 행으로 저장된다. */
public class ScheduleDto {

    /** api-spec 의 priority 는 FAIRNESS | COST, DB 는 COST_SAVING. */
    public static Schedule.Priority toPriority(String value) {
        if (value == null) return Schedule.Priority.FAIRNESS;
        return "COST".equals(value) ? Schedule.Priority.COST_SAVING : Schedule.Priority.valueOf(value);
    }

    public static String fromPriority(Schedule.Priority p) {
        return p == Schedule.Priority.COST_SAVING ? "COST" : "FAIRNESS";
    }

    // ---------- 공통 조각 ----------

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ShiftBody {
        /** 클라이언트가 만든 UUID. 서버는 그대로 저장/반환한다. */
        private String id;
        private Long staffId;
        private String date;
        private String start;
        private String end;
        private String partId;
        private String breakStart;
        private int breakMinutes;
        private boolean tentative;

        public Shift toEntity(Schedule schedule, Worker worker, Part part) {
            Shift s = new Shift();
            s.setId(id);
            s.setSchedule(schedule);
            s.setWorker(worker);
            s.setPart(part);
            s.setWorkDate(LocalDate.parse(date));
            s.setStartTime(TimeText.parse(start));
            s.setEndTime(TimeText.parse(end));
            s.setBreakMinutes(breakMinutes);
            s.setBreakStartTime(TimeText.parse(breakStart));
            s.setStatus(tentative ? Shift.Status.TENTATIVE : Shift.Status.NORMAL);
            return s;
        }

        public static ShiftBody from(Shift s) {
            return ShiftBody.builder()
                    .id(s.getId())
                    .staffId(s.getWorker().getId())
                    .date(s.getWorkDate().toString())
                    .start(TimeText.format(s.getStartTime()))
                    .end(TimeText.formatEnd(s.getStartTime(), s.getEndTime()))
                    .partId(s.getPart() == null ? null : String.valueOf(s.getPart().getId()))
                    .breakStart(TimeText.format(s.getBreakStartTime()))
                    .breakMinutes(s.getBreakMinutes() == null ? 0 : s.getBreakMinutes())
                    .tentative(s.getStatus() == Shift.Status.TENTATIVE)
                    .build();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LeaveBody {
        private String id;
        private Long staffId;
        private String date;
        private String type;

        /** 연차는 시간 없이 ANNUAL_LEAVE 상태로 저장한다(유급 8h 계산은 서비스에서). */
        public Shift toEntity(Schedule schedule, Worker worker) {
            Shift s = new Shift();
            s.setId(id);
            s.setSchedule(schedule);
            s.setWorker(worker);
            s.setWorkDate(LocalDate.parse(date));
            s.setBreakMinutes(0);
            s.setStatus(Shift.Status.ANNUAL_LEAVE);
            return s;
        }

        public static LeaveBody from(Shift s) {
            return LeaveBody.builder()
                    .id(s.getId())
                    .staffId(s.getWorker().getId())
                    .date(s.getWorkDate().toString())
                    .type("ANNUAL")
                    .build();
        }
    }

    @Getter
    @AllArgsConstructor
    public static class Policy {
        private Schedule.WeeklyHolidayPay weeklyHolidayPay;
        private String priority;
    }

    // ---------- 요청 ----------

    /** POST /schedules (직접 만들기) */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class CreateRequest {
        @NotNull
        private LocalDate periodStart;
        private boolean prefillFixed;
    }

    /** POST /schedules/generate (AI) */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class GenerateRequest {
        @NotNull
        private LocalDate periodStart;
        private Schedule.WeeklyHolidayPay weeklyHolidayPay = Schedule.WeeklyHolidayPay.MINIMIZE;
        private String priority = "FAIRNESS";
        private boolean fillContractHours;

        public Schedule.Priority priorityEnum() {
            return toPriority(priority);
        }
    }

    /** PUT /schedules/{id}/shifts - 통째 교체 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SaveShiftsRequest {
        private List<ShiftBody> shifts = new ArrayList<>();
        private List<LeaveBody> leaves = new ArrayList<>();
    }

    // ---------- 응답 ----------

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String periodStart;
        private String periodEnd;
        private Schedule.Status status;
        private Schedule.GenerationType source;
        private Policy policy;
        private List<ShiftBody> shifts;
        private List<LeaveBody> leaves;
        private String aiComment;
        private String updatedAt;

        public static Response from(Schedule s) {
            YearMonth ym = YearMonth.parse(s.getTargetYearMonth());
            List<Shift> sorted = s.getShifts().stream()
                    .sorted(Comparator.comparing(Shift::getWorkDate)
                            .thenComparing(Shift::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            return Response.builder()
                    .id(s.getId())
                    .periodStart(ym.atDay(1).toString())
                    .periodEnd(ym.atEndOfMonth().toString())
                    .status(s.getStatus())
                    .source(s.getGenerationType())
                    .policy(new Policy(
                            s.getWeeklyHolidayPay() == null ? Schedule.WeeklyHolidayPay.ALLOW : s.getWeeklyHolidayPay(),
                            fromPriority(s.getPriority())))
                    .shifts(sorted.stream()
                            .filter(x -> x.getStatus() == Shift.Status.NORMAL || x.getStatus() == Shift.Status.TENTATIVE)
                            .map(ShiftBody::from).toList())
                    .leaves(sorted.stream()
                            .filter(x -> x.getStatus() == Shift.Status.ANNUAL_LEAVE)
                            .map(LeaveBody::from).toList())
                    .aiComment(s.getAiComment() == null ? "" : s.getAiComment())
                    .updatedAt(s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString())
                    .build();
        }
    }
}