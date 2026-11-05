package com.example.autoschedule.service;

import com.example.autoschedule.entity.Shift;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShiftValidatorTest {

    static final LocalDate MON = LocalDate.of(2026, 11, 2);

    static WorkBlock work(String id, long worker, boolean minor, LocalDate date, int start, int end, int brk) {
        return new WorkBlock(id, worker, "w" + worker, minor, date, start, end, brk, Shift.Status.NORMAL);
    }

    static WorkBlock leave(String id, long worker, LocalDate date) {
        return new WorkBlock(id, worker, "w" + worker, false, date, null, null, 0, Shift.Status.ANNUAL_LEAVE);
    }

    @Test
    void separateShiftsPass() {
        assertThat(ShiftValidator.collect(List.of(
                work("a", 1, false, MON, 540, 720, 0),
                work("b", 1, false, MON, 780, 1080, 0),
                work("c", 2, false, MON, 540, 1080, 60)))).isEmpty();
    }

    @Test
    void overlapIsConflict() {
        var v = ShiftValidator.collect(List.of(
                work("a", 1, false, MON, 540, 720, 0),
                work("b", 1, false, MON, 700, 900, 0)));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(v.get(0).blockIds()).containsExactly("b");
    }

    @Test
    void overnightShiftOverlapsNextDay() {
        var v = ShiftValidator.collect(List.of(
                work("a", 1, false, MON, 1320, 1560, 0), // 22:00~26:00
                work("b", 1, false, MON.plusDays(1), 60, 300, 0))); // 01:00~05:00
        assertThat(v).extracting(ShiftValidator.Violation::blockIds).containsExactly(List.of("b"));
        assertThat(ShiftValidator.collect(List.of(
                work("a", 1, false, MON, 1320, 1560, 0),
                work("b", 1, false, MON.plusDays(1), 120, 300, 0)))).isEmpty();
    }

    @Test
    void workOnLeaveDayIsConflict() {
        var v = ShiftValidator.collect(List.of(leave("l", 1, MON), work("a", 1, false, MON, 540, 720, 0)));
        assertThat(v).extracting(ShiftValidator.Violation::status).containsExactly(HttpStatus.CONFLICT);
    }

    @Test
    void minorRules() {
        assertThat(ShiftValidator.collect(List.of(work("a", 1, true, MON, 1080, 1380, 0))))
                .anyMatch(x -> x.message().contains("22:00"));
        assertThat(ShiftValidator.collect(List.of(work("a", 1, true, MON, 540, 1050, 30)))) // 8h net
                .anyMatch(x -> x.message().contains("7시간"));

        List<WorkBlock> sevenHourDays = new ArrayList<>();
        for (int i = 0; i < 5; i++) sevenHourDays.add(work("d" + i, 1, true, MON.plusDays(i), 540, 960, 0));
        assertThat(ShiftValidator.collect(sevenHourDays)).isEmpty(); // exactly 35h
        sevenHourDays.add(work("d5", 1, true, MON.plusDays(5), 540, 600, 0));
        assertThat(ShiftValidator.collect(sevenHourDays)).anyMatch(x -> x.message().contains("35시간"));
    }
}
