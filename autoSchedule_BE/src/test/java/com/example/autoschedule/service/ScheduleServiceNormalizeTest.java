package com.example.autoschedule.service;

import com.example.autoschedule.dto.ScheduleDto.ShiftBody;
import com.example.autoschedule.entity.Part;
import com.example.autoschedule.entity.Schedule;
import com.example.autoschedule.entity.Shift;
import com.example.autoschedule.entity.Worker;
import com.example.autoschedule.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleServiceNormalizeTest {

    static Worker worker(Worker.EmploymentType type) {
        Worker w = new Worker();
        w.setId(1L);
        w.setName("w");
        w.setEmploymentType(type);
        w.setHourlyWage(10_000);
        return w;
    }

    static ShiftBody body(String start, String end) {
        return ShiftBody.builder().staffId(1L).date("2026-11-02").start(start).end(end).build();
    }

    @Test
    void fullTimeGetsLegalBreakInTheMiddle() {
        Shift s = ScheduleService.normalize(body("09:00", "18:00"), worker(Worker.EmploymentType.FULL_TIME), null);
        assertThat(s.getBreakMinutes()).isEqualTo(60);
        assertThat(s.getBreakStartTime()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void overnightShiftIsStoredAsClockTime() {
        Shift s = ScheduleService.normalize(body("22:00", "26:00"), worker(Worker.EmploymentType.FULL_TIME), null);
        assertThat(s.getEndTime()).isEqualTo(LocalTime.of(2, 0));
        assertThat(WorkBlock.of(s).endMin()).isEqualTo(1560);
        assertThat(s.getBreakMinutes()).isEqualTo(30);
        assertThat(com.example.autoschedule.dto.TimeText.formatEnd(s.getStartTime(), s.getEndTime())).isEqualTo("26:00");

        assertThatThrownBy(() -> ScheduleService.normalize(body("09:00", "09:00"),
                worker(Worker.EmploymentType.FULL_TIME), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void fullTimeNeedsQuarterHours() {
        assertThatThrownBy(() -> ScheduleService.normalize(body("09:10", "18:00"),
                worker(Worker.EmploymentType.FULL_TIME), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void partTimerUsesPartTimes() {
        Part p = new Part();
        p.setId(7L);
        p.setStartTime(LocalTime.of(10, 0));
        p.setEndTime(LocalTime.of(14, 0));
        p.setBreakMinutes(0);
        Shift s = ScheduleService.normalize(body(null, null), worker(Worker.EmploymentType.PART_TIME), p);
        assertThat(s.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(s.getEndTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(s.getBreakMinutes()).isEqualTo(30); // 4h span needs 30m

        assertThatThrownBy(() -> ScheduleService.normalize(body(null, null),
                worker(Worker.EmploymentType.PART_TIME), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void confirmedScheduleIsReadOnly() {
        Schedule s = new Schedule();
        s.setStatus(Schedule.Status.DRAFT);
        ScheduleService.requireDraft(s);
        s.setStatus(Schedule.Status.CONFIRMED);
        assertThatThrownBy(() -> ScheduleService.requireDraft(s)).isInstanceOf(ApiException.class);
    }
}
