package com.example.autoschedule.ai;

import com.example.autoschedule.ai.AiContext.*;
import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Weekday;
import com.example.autoschedule.service.Minutes;

import java.time.LocalDate;
import java.util.*;

/** 1단계: 매장·구성원·정책을 Gemini 에 보낼 입력 JSON 으로 매핑하고, 시스템/보정 프롬프트를 만든다. */
public final class AiPromptBuilder {

    /** 로그에 남겨 프롬프트 회귀를 추적한다. */
    public static final String PROMPT_VERSION = "schedule-v1";

    private AiPromptBuilder() {}

    // ---------------------------------------------------------------- 입력 JSON

    public record AiInput(Period period, List<Day> calendar, StoreIn store, List<StaffIn> staff, PolicyIn policy,
                          List<Infeasible> infeasibleSlots) {}

    public record Period(String start, String end) {}

    public record Day(String date, String day) {}

    public record StoreIn(String size, Map<String, List<String>> openingHours, List<PartIn> parts,
                          List<DemandIn> demand) {}

    public record PartIn(String id, String name, String start, String end, int breakMinutes) {}

    public record DemandIn(String day, String start, String end, int required) {}

    /** 이름·연락처는 넣지 않는다. id 로만 가리킨다(PRD 8.4 개인정보). */
    public record StaffIn(long id, String employmentType, String title, int hourlyWage, boolean minor,
                          Integer contractWeeklyHours, List<Integer> preferredWeeklyHours,
                          Map<String, List<String>> availableParts, Map<String, List<String>> fixedHours) {}

    public record PolicyIn(String weeklyHolidayPay, String priority, boolean fillContractHours) {}

    public record Infeasible(String day, String start, String end, int required, int candidates, int shortage) {}

    public static AiInput buildInput(AiContext ctx) {
        List<Day> calendar = new ArrayList<>();
        for (LocalDate d = ctx.month().atDay(1); !d.isAfter(ctx.month().atEndOfMonth()); d = d.plusDays(1)) {
            calendar.add(new Day(d.toString(), Weekday.of(d.getDayOfWeek()).name()));
        }

        Map<String, List<String>> hours = new LinkedHashMap<>();
        for (Weekday wd : Weekday.values()) {
            DayHours h = ctx.hours().get(wd);
            if (h != null) hours.put(wd.name(), List.of(Minutes.format(h.openMin()), Minutes.format(h.closeMin())));
        }

        List<PartIn> parts = ctx.parts().values().stream()
                .sorted(Comparator.comparingInt(PartInfo::startMin))
                .map(p -> new PartIn(String.valueOf(p.id()), p.name(), Minutes.format(p.startMin()),
                        Minutes.format(p.endMin()), p.breakMinutes()))
                .toList();

        List<DemandIn> demand = ctx.demand().stream()
                .map(d -> new DemandIn(d.day().name(), Minutes.format(d.startMin()), Minutes.format(d.endMin()), d.required()))
                .toList();

        List<StaffIn> staff = ctx.staff().values().stream().sorted(Comparator.comparingLong(StaffInfo::id)).map(s -> {
            Map<String, List<String>> avail = new LinkedHashMap<>();
            s.partsByDay().forEach((wd, ids) -> {
                if (!ids.isEmpty()) avail.put(wd.name(), ids.stream().map(String::valueOf).toList());
            });
            Map<String, List<String>> fixed = new LinkedHashMap<>();
            s.fixedByDay().forEach((wd, se) -> fixed.put(wd.name(), List.of(Minutes.format(se[0]), Minutes.format(se[1]))));
            List<Integer> pref = (s.minWeeklyHours() == null && s.maxWeeklyHours() == null) ? null
                    : List.of(s.minWeeklyHours() == null ? 0 : s.minWeeklyHours(),
                    s.maxWeeklyHours() == null ? 0 : s.maxWeeklyHours());
            return new StaffIn(s.id(), s.fullTime() ? "FULL_TIME" : "PART_TIME", s.title(), s.hourlyWage(), s.minor(),
                    s.contractWeeklyHours(), pref, avail, fixed);
        }).toList();

        return new AiInput(
                new Period(ctx.month().atDay(1).toString(), ctx.month().atEndOfMonth().toString()),
                calendar,
                new StoreIn(ctx.scale().name(), hours, parts, demand),
                staff,
                new PolicyIn(ctx.weeklyHolidayPay().name(), ctx.priority().name(), ctx.fillContractHours()),
                infeasibleSlots(ctx));
    }

    /** 사전 검증(PRD 8.1-2): 가용 인력으로 필요 인원을 채울 수 없는 요일·시간대. */
    static List<Infeasible> infeasibleSlots(AiContext ctx) {
        List<Infeasible> out = new ArrayList<>();
        for (DemandInfo d : ctx.demand()) {
            int candidates = 0;
            for (StaffInfo s : ctx.staff().values()) {
                if (canCover(ctx, s, d)) candidates++;
            }
            if (candidates < d.required()) {
                out.add(new Infeasible(d.day().name(), Minutes.format(d.startMin()), Minutes.format(d.endMin()),
                        d.required(), candidates, d.required() - candidates));
            }
        }
        return out;
    }

    private static boolean canCover(AiContext ctx, StaffInfo s, DemandInfo d) {
        if (s.fullTime()) {
            if (s.fixedByDay().isEmpty()) return true; // 시간 자유
            int[] se = s.fixedByDay().get(d.day());
            return se != null && se[0] < d.endMin() && se[1] > d.startMin();
        }
        for (Long partId : s.partsByDay().getOrDefault(d.day(), List.of())) {
            PartInfo p = ctx.parts().get(partId);
            if (p != null && p.startMin() < d.endMin() && p.endMin() > d.startMin()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 프롬프트

    public static final String SYSTEM_PROMPT = """
            당신은 소상공인 매장의 월간 근무표를 짜는 스케줄러입니다. 입력 JSON을 읽고 근무표를 JSON으로만 출력하세요.

            [근무 방식]
            - FULL_TIME(직원): 파트 없이 날마다 출근/퇴근 시각을 직접 정합니다. 15분 단위("HH:MM"), partId는 null.
              fixedHours가 있으면 그 요일은 해당 시각 그대로 쓰고, fixedHours가 있는데 해당 요일이 없으면 쉬는 날이라 배치하지 않습니다.
              fixedHours가 비어 있으면 시간이 자유이므로 인력이 부족한 시간대에 맞춰 날마다 다르게 정합니다.
              fillContractHours가 true이면 직원의 contractWeeklyHours(주 소정근로시간)를 먼저 채우세요.
            - PART_TIME(알바생): 개별 시각이 아니라 store.parts의 파트 단위로만 배치합니다. partId에 파트 id 문자열을 넣고,
              start/end는 그 파트의 시각과 같게 씁니다. availableParts에 있는 요일·파트에만 배치할 수 있습니다.

            [반드시 지킬 규칙 - 위반하면 서버가 거부합니다]
            1. 한 사람은 하루에 근무 1건만, 같은 사람의 근무 시간이 겹치면 안 됩니다.
            2. 근무는 openingHours(영업시간) 안에서만 하며, 영업시간이 없는 요일에는 배치하지 않습니다.
            3. minor=true(연소자)는 22:00 이후까지 근무하면 안 되고, 하루 7시간(휴게 제외), 주 35시간(월~일)을 넘기면 안 됩니다.
            4. 입력에 없는 staffId, 파트 id, 날짜를 만들지 마세요. date는 calendar에 있는 날짜만 씁니다.

            [최적화 방향]
            - store.demand의 요일·시간대별 required(필요 인원)를 가능한 한 채웁니다. 채울 수 없으면 unfilled에 적습니다.
              infeasibleSlots는 처음부터 인원이 모자란 시간대이니 억지로 채우지 마세요.
            - policy.weeklyHolidayPay: MINIMIZE면 알바생을 가능하면 주 15시간 미만으로 배치, ALLOW면 희망 근무시간(preferredWeeklyHours)을 우선합니다.
            - policy.priority: FAIRNESS면 알바생 간 근무시간 차이를 줄이고, COST_SAVING이면 필요 인원만큼만 최소로 배치합니다.
            - 알바생의 preferredWeeklyHours([최소, 최대])를 가능하면 지킵니다.

            [출력]
            - 지정된 JSON 스키마만 출력합니다. staffId는 숫자, date는 "YYYY-MM-DD".
            - notes에는 사장님께 드리는 2~3문장 요약을 쓰고, 구성원은 이름이 아니라 "#번호"로 부르세요.
              시간·인원·금액 합계 같은 숫자는 직접 계산해서 쓰지 마세요(서버가 계산합니다).
            """;

    public static String userMessage(String inputJson) {
        return "다음 입력으로 근무표를 만들어 주세요.\n" + inputJson;
    }

    /** 3단계 보정 루프: 위반 사유를 붙여 전체 근무표를 다시 요청한다. */
    public static String correctionMessage(List<String> violations) {
        StringBuilder sb = new StringBuilder("직전 근무표에서 다음 규칙 위반이 발견되었습니다. ")
                .append("이 위반을 모두 해결하고, 문제없던 근무는 가급적 유지해서 전체 근무표를 같은 JSON 형식으로 다시 출력하세요.\n");
        int limit = 30;
        for (int i = 0; i < violations.size() && i < limit; i++) sb.append("- ").append(violations.get(i)).append('\n');
        if (violations.size() > limit) sb.append("- 그 외 ").append(violations.size() - limit).append("건\n");
        return sb.toString();
    }

    public static String formatErrorMessage(String detail) {
        return "응답이 지정한 JSON 스키마와 맞지 않아 읽을 수 없습니다(" + detail + "). 스키마에 맞는 JSON만 다시 출력하세요.";
    }

    // ---------------------------------------------------------------- 출력 스키마 (Gemini responseSchema)

    public static Map<String, Object> responseSchema() {
        Map<String, Object> shift = obj(Map.of(
                "staffId", Map.of("type", "INTEGER"),
                "date", Map.of("type", "STRING"),
                "start", Map.of("type", "STRING"),
                "end", Map.of("type", "STRING"),
                "partId", Map.of("type", "STRING", "nullable", true)),
                List.of("staffId", "date", "start", "end"));
        Map<String, Object> unfilled = obj(Map.of(
                "date", Map.of("type", "STRING"),
                "partId", Map.of("type", "STRING", "nullable", true),
                "shortage", Map.of("type", "INTEGER"),
                "reason", Map.of("type", "STRING")),
                List.of("date", "shortage"));
        return obj(Map.of(
                "shifts", Map.of("type", "ARRAY", "items", shift),
                "unfilled", Map.of("type", "ARRAY", "items", unfilled),
                "notes", Map.of("type", "STRING")),
                List.of("shifts", "notes"));
    }

    private static Map<String, Object> obj(Map<String, Object> props, List<String> required) {
        return Map.of("type", "OBJECT", "properties", props, "required", required);
    }

    static String priorityLabel(Schedule.Priority p) {
        return p == Schedule.Priority.COST_SAVING ? "COST_SAVING" : "FAIRNESS";
    }
}