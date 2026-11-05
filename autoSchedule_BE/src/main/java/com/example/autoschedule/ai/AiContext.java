package com.example.autoschedule.ai;

import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Store;
import com.example.autoschedule.entity.Weekday;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * AI 생성에 필요한 데이터의 불변 스냅샷. DB 트랜잭션 안에서 만든 뒤에는 엔티티를 건드리지 않으므로,
 * 수 분 걸리는 외부 호출 동안 DB 연결을 잡고 있지 않는다. 시각은 자정 기준 분(자정을 넘는 종료는 1440 이상).
 */
public record AiContext(
        Long storeId,
        Store.Scale scale,
        YearMonth month,
        Map<Long, StaffInfo> staff,
        Map<Long, PartInfo> parts,
        Map<Weekday, DayHours> hours,
        List<DemandInfo> demand,
        Schedule.WeeklyHolidayPay weeklyHolidayPay,
        Schedule.Priority priority,
        boolean fillContractHours) {

    public record StaffInfo(long id, String name, boolean fullTime, boolean minor, int hourlyWage, String title,
                            Integer contractWeeklyHours, Integer minWeeklyHours, Integer maxWeeklyHours,
                            /** 알바생: 요일별 근무 가능 파트 id */
                            Map<Weekday, List<Long>> partsByDay,
                            /** 직원: 요일별 고정 출퇴근 [시작, 종료]. 비어 있으면 시간 자유 */
                            Map<Weekday, int[]> fixedByDay) {}

    public record PartInfo(long id, String name, int startMin, int endMin, int breakMinutes) {}

    public record DayHours(int openMin, int closeMin) {}

    public record DemandInfo(Weekday day, int startMin, int endMin, int required) {}
}