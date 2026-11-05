package com.example.autoschedule.dto;

import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Worker;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** 대시보드 리포트 응답. 금액은 원, 시간은 시간(소수 둘째 자리) 단위이며 모두 서버가 계산한다. */
public class ScheduleReportDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long scheduleId;
        private String month;
        private Schedule.Status status;
        /** 5인 이상 사업장이라 연장/야간 가산수당을 계산에 포함했는지 */
        private boolean premiumApplied;
        private Summary summary;
        private List<WorkerRow> workers;
        /** 계산의 한계(이웃 달 근무표 없음 등) 안내 */
        private List<String> warnings;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private double totalWorkHours;
        /** 기본급 (직원 연차 유급 8h 포함) */
        private long basePay;
        private long weeklyHolidayPay;
        /** 연장근로 가산분(50%) - 5인 이상만 */
        private long overtimePremium;
        /** 야간근로 가산분(50%) - 5인 이상만 */
        private long nightPremium;
        private long totalLaborCost;
        /** 주 15시간 이상 배치된 알바생 수(이 달에 귀속되는 주 기준) */
        private int holidayPayTargetCount;
        private List<Target> holidayPayTargets;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Target {
        private Long workerId;
        private String name;
        /** 주휴수당이 발생한 주 수 */
        private int qualifyingWeeks;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerRow {
        private Long workerId;
        private String name;
        private Worker.EmploymentType employmentType;
        private double workHours;
        private double leaveHours;
        private double overtimeHours;
        private double nightHours;
        private long basePay;
        private long weeklyHolidayPay;
        private long overtimePremium;
        private long nightPremium;
        private long totalPay;
        private List<WeekRow> weeks;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeekRow {
        /** 해당 주 월요일 */
        private String weekStart;
        /** 그 주 소정근로시간(직원 연차 포함, 이웃 달 근무 포함) */
        private double workHours;
        private boolean holidayPayEligible;
        /** 월요일이 이 달에 속해 이 달 주휴수당에 반영된 주 */
        private boolean countedInThisMonth;
    }
}