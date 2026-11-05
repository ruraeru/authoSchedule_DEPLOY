package com.example.autoschedule.auth;

import com.example.autoschedule.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAfterMaxFailuresUntilWindowEnds() {
        MutableClock clock = new MutableClock();
        LoginThrottle t = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) t.recordFailure("a@x");
        assertThatCode(() -> t.checkAllowed("a@x")).doesNotThrowAnyException();

        t.recordFailure("a@x");
        assertThatThrownBy(() -> t.checkAllowed("a@x")).isInstanceOfSatisfying(ApiException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatCode(() -> t.checkAllowed("b@x")).doesNotThrowAnyException();

        clock.now = clock.now.plus(LoginThrottle.WINDOW).plus(Duration.ofSeconds(1));
        assertThatCode(() -> t.checkAllowed("a@x")).doesNotThrowAnyException();
    }

    @Test
    void resetClearsFailures() {
        LoginThrottle t = new LoginThrottle(new MutableClock());
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) t.recordFailure("a@x");
        t.reset("a@x");
        assertThatCode(() -> t.checkAllowed("a@x")).doesNotThrowAnyException();
    }
}
