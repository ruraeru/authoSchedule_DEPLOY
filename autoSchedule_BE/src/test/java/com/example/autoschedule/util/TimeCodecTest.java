package com.example.autoschedule.util;

import com.example.autoschedule.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeCodecTest {

    @Test
    void endPastMidnightIsStoredAsClockTime() {
        assertThat(TimeCodec.end(LocalTime.of(18, 0), "26:00")).isEqualTo(LocalTime.of(2, 0));
        assertThat(TimeCodec.formatEnd(LocalTime.of(18, 0), LocalTime.of(2, 0))).isEqualTo("26:00");
        assertThat(TimeCodec.formatEnd(LocalTime.of(9, 0), LocalTime.of(18, 0))).isEqualTo("18:00");
        assertThat(TimeCodec.formatEnd(LocalTime.of(9, 0), LocalTime.of(9, 0))).isEqualTo("33:00");
    }

    @Test
    void rejectsInvalidRanges() {
        assertThatThrownBy(() -> TimeCodec.start("24:00")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeCodec.end(LocalTime.of(18, 0), "17:00")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TimeCodec.end(LocalTime.of(18, 0), "42:01")).isInstanceOf(ApiException.class);
    }
}
