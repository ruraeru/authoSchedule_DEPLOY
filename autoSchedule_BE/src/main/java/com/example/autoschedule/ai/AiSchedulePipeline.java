package com.example.autoschedule.ai;

import com.example.autoschedule.ai.AiContext.*;
import com.example.autoschedule.ai.ScheduleAiClient.Turn;
import com.example.autoschedule.entity.Shift;
import com.example.autoschedule.entity.Weekday;
import com.example.autoschedule.exception.ApiException;
import com.example.autoschedule.service.BreakCalculator;
import com.example.autoschedule.service.Minutes;
import com.example.autoschedule.service.ShiftValidator;
import com.example.autoschedule.service.WorkBlock;
import com.example.autoschedule.dto.TimeText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * 2~3단계: Gemini 호출 → 파싱 → 규칙 검증 → (위반 시) 위반 사유를 붙여 최대 2회 재생성.
 * DB 를 모르는 순수 파이프라인이다. 마지막 시도에도 위반이 남으면 위반 줄을 버려 결과가 항상 하드 제약을 통과하도록 한다.
 * 전체 시간 예산(gemini.total-budget-seconds)을 넘기면 추가 호출(보정·형식 재요청)을 하지 않는다.
 * 이미 시작한 호출은 끊지 않으므로 최악의 경우는 예산 + 호출 1번(모델 폴백 포함)이다.
 */
@Slf4j
@Component
public class AiSchedulePipeline {

    /** 보정(재생성) 최대 횟수. 첫 호출은 별도. */
    public static final int MAX_CORRECTIONS = 2;

    public record AcceptedLine(long staffId, LocalDate date, Long partId, int startMin, int endMin) {}

    public record Result(List<AcceptedLine> lines, String notes, int unfilledCount, int removedCount,
                         int calls, int corrections) {}

    private record Checked(List<AcceptedLine> lines, List<String> violations) {}

    private final ScheduleAiClient client;
    private final JsonMapper mapper;
    private final long budgetMillis;
    private final LongSupplier clock;

    @Autowired
    public AiSchedulePipeline(ScheduleAiClient client, JsonMapper mapper,
                              @Value("${gemini.total-budget-seconds:180}") long budgetSeconds) {
        this(client, mapper, budgetSeconds * 1000, System::currentTimeMillis);
    }

    AiSchedulePipeline(ScheduleAiClient client, JsonMapper mapper, long budgetMillis, LongSupplier clock) {
        this.client = client;
        this.mapper = mapper;
        this.budgetMillis = budgetMillis;
        this.clock = clock;
    }

    public Result run(AiContext ctx) {
        long t0 = clock.getAsLong();
        String inputJson = mapper.writeValueAsString(AiPromptBuilder.buildInput(ctx));
        List<Turn> turns = new ArrayList<>();
        turns.add(Turn.user(AiPromptBuilder.userMessage(inputJson)));

        int calls = 0;
        int corrections = 0;
        while (true) {
            Parsed parsed = callAndParse(turns, t0);
            calls += parsed.calls;
            AiOutput out = parsed.output;

            Checked checked = check(ctx, out);
            List<WorkBlock> blocks = toBlocks(ctx, checked.lines);
            List<ShiftValidator.Violation> hard = ShiftValidator.collect(blocks);

            List<String> all = new ArrayList<>(checked.violations);
            hard.forEach(v -> all.add(v.message()));
            int unfilled = out.unfilled() == null ? 0 : out.unfilled().size();

            if (all.isEmpty()) {
                log.info("AI schedule ok: promptVersion={} calls={} corrections={} ms={}",
                        AiPromptBuilder.PROMPT_VERSION, calls, corrections, clock.getAsLong() - t0);
                return new Result(checked.lines, out.notes(), unfilled, 0, calls, corrections);
            }
            if (corrections >= MAX_CORRECTIONS || overBudget(t0)) {
                List<AcceptedLine> kept = new ArrayList<>(checked.lines);
                int removed = dropUntilValid(ctx, kept);
                log.warn("AI schedule still violating after {} corrections; dropped {} lines. promptVersion={} ms={}",
                        corrections, removed + checked.violations.size(), AiPromptBuilder.PROMPT_VERSION,
                        clock.getAsLong() - t0);
                return new Result(kept, out.notes(), unfilled, removed + checked.violations.size(), calls, corrections);
            }
            log.info("AI schedule has {} violations; requesting correction {}", all.size(), corrections + 1);
            turns.add(Turn.model(parsed.raw));
            turns.add(Turn.user(AiPromptBuilder.correctionMessage(all)));
            corrections++;
        }
    }

    // ---------------------------------------------------------------- 호출 + 파싱 (형식 오류는 1회 재요청)

    private record Parsed(AiOutput output, String raw, int calls) {}

    private Parsed callAndParse(List<Turn> turns, long t0) {
        String raw = client.generate(AiPromptBuilder.SYSTEM_PROMPT, turns, AiPromptBuilder.responseSchema());
        AiOutput out = tryParse(raw);
        if (out != null) return new Parsed(out, raw, 1);
        if (overBudget(t0)) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 응답을 해석하지 못했습니다. 다시 시도하세요.");

        List<Turn> retry = new ArrayList<>(turns);
        retry.add(Turn.model(raw));
        retry.add(Turn.user(AiPromptBuilder.formatErrorMessage("JSON 파싱 실패")));
        String raw2 = client.generate(AiPromptBuilder.SYSTEM_PROMPT, retry, AiPromptBuilder.responseSchema());
        AiOutput out2 = tryParse(raw2);
        if (out2 == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 응답을 해석하지 못했습니다. 다시 시도하세요.");
        return new Parsed(out2, raw2, 2);
    }

    private boolean overBudget(long t0) {
        return clock.getAsLong() - t0 >= budgetMillis;
    }

    private AiOutput tryParse(String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            AiOutput out = mapper.readValue(json, AiOutput.class);
            if (out == null || out.shifts() == null) return null;
            return out;
        } catch (RuntimeException e) {
            log.warn("AI response parse failed: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 줄 단위 검사 (api-spec toShifts + R-01)

    private Checked check(AiContext ctx, AiOutput out) {
        List<AcceptedLine> lines = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (AiOutput.AiShift s : out.shifts()) {
            String tag = "#" + s.staffId() + " " + s.date();
            StaffInfo st = s.staffId() == null ? null : ctx.staff().get(s.staffId());
            if (st == null) {
                violations.add("존재하지 않는 staffId입니다: " + tag);
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(s.date());
            } catch (RuntimeException e) {
                violations.add(tag + ": 날짜 형식이 올바르지 않습니다.");
                continue;
            }
            if (!ctx.month().equals(java.time.YearMonth.from(date))) {
                violations.add(tag + ": 생성 기간(" + ctx.month() + ") 밖의 날짜입니다.");
                continue;
            }
            Weekday wd = Weekday.of(date.getDayOfWeek());

            int start;
            int end;
            Long partId = null;
            if (st.fullTime()) {
                int[] fixed = st.fixedByDay().get(wd);
                if (fixed != null) {
                    start = fixed[0];
                    end = fixed[1];
                } else if (!st.fixedByDay().isEmpty()) {
                    violations.add(tag + ": 고정 근무가 없는 쉬는 요일입니다.");
                    continue;
                } else {
                    try {
                        LocalTime a = TimeText.parse(s.start());
                        LocalTime b = TimeText.parse(s.end());
                        start = snap15(Minutes.ofStart(a));
                        end = snap15(Minutes.ofEnd(a, b));
                    } catch (RuntimeException e) {
                        violations.add(tag + ": 출퇴근 시각 형식이 올바르지 않습니다. (HH:MM)");
                        continue;
                    }
                }
                if (start % 15 != 0 || end % 15 != 0) {
                    violations.add(tag + ": 직원 출퇴근 시각은 15분 단위여야 합니다.");
                    continue;
                }
            } else {
                PartInfo part = null;
                try {
                    if (s.partId() != null && !s.partId().isBlank()) part = ctx.parts().get(Long.valueOf(s.partId()));
                } catch (NumberFormatException ignored) {
                    // part stays null
                }
                if (part == null) {
                    violations.add(tag + ": 알바생은 유효한 partId로 배치해야 합니다.");
                    continue;
                }
                if (!st.partsByDay().getOrDefault(wd, List.of()).contains(part.id())) {
                    violations.add(tag + ": 알바생이 근무할 수 없는 요일/파트입니다. (" + part.name() + ")");
                    continue;
                }
                partId = part.id();
                start = part.startMin();
                end = part.endMin();
            }
            if (end <= start || end - start >= Minutes.DAY) {
                violations.add(tag + ": 퇴근 시각이 출근 시각보다 늦어야 합니다.");
                continue;
            }
            if (!ctx.hours().isEmpty()) {
                DayHours h = ctx.hours().get(wd);
                if (h == null || start < h.openMin() || end > h.closeMin()) {
                    violations.add(tag + ": 영업시간 밖입니다. (" + Minutes.format(start) + "~" + Minutes.format(end) + ")");
                    continue;
                }
            }
            if (!seen.add(st.id() + "|" + date)) {
                violations.add(tag + ": 한 사람은 하루에 근무 1건만 가능합니다.");
                continue;
            }
            lines.add(new AcceptedLine(st.id(), date, partId, start, end));
        }
        return new Checked(lines, violations);
    }

    private static int snap15(int minutes) {
        return (int) Math.round(minutes / 15.0) * 15;
    }

    // ---------------------------------------------------------------- 규칙 엔진 연결

    /** 이름은 프롬프트에 노출하지 않으므로 "#id" 로 부른다. 휴게는 저장 시와 같은 공식으로 계산한다. */
    private static List<WorkBlock> toBlocks(AiContext ctx, List<AcceptedLine> lines) {
        List<WorkBlock> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            AcceptedLine l = lines.get(i);
            StaffInfo st = ctx.staff().get(l.staffId());
            PartInfo part = l.partId() == null ? null : ctx.parts().get(l.partId());
            int brk = BreakCalculator.resolve(l.endMin() - l.startMin(), 0, part == null ? null : part.breakMinutes());
            blocks.add(new WorkBlock("ai-" + i, st.id(), "#" + st.id(), st.minor(), l.date(),
                    l.startMin(), l.endMin(), brk, Shift.Status.NORMAL));
        }
        return blocks;
    }

    /** 마지막 시도: 위반이 풀릴 때까지 위반에 걸린 줄을 하나씩 버린다. 버린 줄 수를 돌려준다. */
    private static int dropUntilValid(AiContext ctx, List<AcceptedLine> lines) {
        int removed = 0;
        while (!lines.isEmpty()) {
            List<ShiftValidator.Violation> vs = ShiftValidator.collect(toBlocks(ctx, lines));
            if (vs.isEmpty()) break;
            List<String> ids = vs.get(0).blockIds();
            if (ids.isEmpty()) break;
            int index = Integer.parseInt(ids.get(ids.size() - 1).substring("ai-".length()));
            lines.remove(index);
            removed++;
        }
        return removed;
    }
}