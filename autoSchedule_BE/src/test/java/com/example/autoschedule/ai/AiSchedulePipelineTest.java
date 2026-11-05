package com.example.autoschedule.ai;

import com.example.autoschedule.ai.AiContext.PartInfo;
import com.example.autoschedule.ai.AiContext.StaffInfo;
import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Store;
import com.example.autoschedule.entity.Weekday;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AiSchedulePipelineTest {

    static final String VALID = "{\"shifts\":[{\"staffId\":1,\"date\":\"2026-11-02\",\"partId\":\"7\"}],\"notes\":\"ok\"}";
    static final String UNKNOWN_STAFF = "{\"shifts\":[{\"staffId\":1,\"date\":\"2026-11-02\",\"partId\":\"7\"},"
            + "{\"staffId\":99,\"date\":\"2026-11-02\",\"partId\":\"7\"}],\"notes\":\"\"}";

    /** Returns canned responses in order; repeats the last one. */
    static class FakeClient implements ScheduleAiClient {
        final Deque<String> replies;
        int calls;
        /** fake clock in ms, advanced by callMillis per call */
        long now;
        long callMillis;

        FakeClient(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public String generate(String systemPrompt, List<Turn> turns, Map<String, Object> responseSchema) {
            calls++;
            now += callMillis;
            return replies.size() > 1 ? replies.poll() : replies.peek();
        }
    }

    static AiContext ctx() {
        StaffInfo staff = new StaffInfo(1, "a", false, false, 10_000, null, null, null, null,
                Map.of(Weekday.MON, List.of(7L)), Map.of());
        return new AiContext(1L, Store.Scale.UNDER_5, YearMonth.of(2026, 11), Map.of(1L, staff),
                Map.of(7L, new PartInfo(7, "open", 600, 840, 0)), Map.of(), List.of(),
                Schedule.WeeklyHolidayPay.ALLOW, Schedule.Priority.FAIRNESS, false);
    }

    static AiSchedulePipeline pipeline(FakeClient client) {
        return new AiSchedulePipeline(client, JsonMapper.builder().build(), Long.MAX_VALUE, () -> client.now);
    }

    @Test
    void overnightPartFitsOvernightStoreHours() {
        StaffInfo staff = new StaffInfo(1, "a", false, false, 10_000, null, null, null, null,
                Map.of(Weekday.MON, List.of(8L)), Map.of());
        AiContext c = new AiContext(1L, Store.Scale.UNDER_5, YearMonth.of(2026, 11), Map.of(1L, staff),
                Map.of(8L, new PartInfo(8, "night", 1320, 1560, 0)),
                Map.of(Weekday.MON, new AiContext.DayHours(600, 1560)), List.of(),
                Schedule.WeeklyHolidayPay.ALLOW, Schedule.Priority.FAIRNESS, false);
        var r = pipeline(new FakeClient(VALID.replace("\"7\"", "\"8\""))).run(c);
        assertThat(r.lines()).extracting(AiSchedulePipeline.AcceptedLine::endMin).containsExactly(1560);
        assertThat(r.removedCount()).isZero();
    }

    @Test
    void validFirstReply() {
        var r = pipeline(new FakeClient(VALID)).run(ctx());
        assertThat(r.lines()).hasSize(1);
        assertThat(r.calls()).isEqualTo(1);
        assertThat(r.corrections()).isZero();
    }

    @Test
    void correctsOnceThenAccepts() {
        var r = pipeline(new FakeClient(UNKNOWN_STAFF, VALID)).run(ctx());
        assertThat(r.lines()).hasSize(1);
        assertThat(r.corrections()).isEqualTo(1);
        assertThat(r.removedCount()).isZero();
    }

    @Test
    void dropsBadLinesAfterMaxCorrections() {
        FakeClient client = new FakeClient(UNKNOWN_STAFF);
        var r = pipeline(client).run(ctx());
        assertThat(r.lines()).hasSize(1);
        assertThat(r.removedCount()).isEqualTo(1);
        assertThat(client.calls).isEqualTo(1 + AiSchedulePipeline.MAX_CORRECTIONS);
    }

    @Test
    void stopsCorrectingWhenBudgetIsSpent() {
        FakeClient client = new FakeClient(UNKNOWN_STAFF);
        client.callMillis = 100_000;
        var r = new AiSchedulePipeline(client, JsonMapper.builder().build(), 150_000, () -> client.now).run(ctx());
        assertThat(client.calls).isEqualTo(2); // 0s -> 100s (under budget, correct once) -> 200s (stop)
        assertThat(r.corrections()).isEqualTo(1);
        assertThat(r.lines()).hasSize(1);
        assertThat(r.removedCount()).isEqualTo(1);
    }

    @Test
    void unparsableReplyIsRetriedOnce() {
        FakeClient client = new FakeClient("not json", VALID);
        var r = pipeline(client).run(ctx());
        assertThat(r.lines()).hasSize(1);
        assertThat(client.calls).isEqualTo(2);
    }
}
