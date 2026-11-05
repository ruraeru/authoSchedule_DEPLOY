package com.example.autoschedule.service;

import com.example.autoschedule.entity.Shift;

import java.time.LocalDate;

/** 검증용 근무 블록. JPA 와 무관한 값 객체라 단독으로 테스트할 수 있다. 시각은 근무일 자정 기준 분(자정을 넘는 종료는 1440 이상). */
public record WorkBlock(String id, Long workerId, String workerName, boolean minor, LocalDate date,
                        Integer startMin, Integer endMin, int breakMinutes, Shift.Status status) {

    public boolean isWorking() {
        return status == Shift.Status.NORMAL || status == Shift.Status.TENTATIVE;
    }

    public boolean isAbsence() {
        return status == Shift.Status.ANNUAL_LEAVE || status == Shift.Status.DAY_OFF;
    }

    public boolean hasTime() {
        return startMin != null && endMin != null;
    }

    /** 휴게를 뺀 실근로(분). 시간 미정이면 0. */
    public int netMinutes() {
        return hasTime() ? Math.max(0, endMin - startMin - breakMinutes) : 0;
    }

    public static WorkBlock of(Shift s) {
        Integer start = s.getStartTime() == null ? null : Minutes.ofStart(s.getStartTime());
        Integer end = (s.getStartTime() == null || s.getEndTime() == null) ? null
                : Minutes.ofEnd(s.getStartTime(), s.getEndTime());
        return new WorkBlock(s.getId(), s.getWorker().getId(), s.getWorker().getName(), s.getWorker().isMinor(),
                s.getWorkDate(), start, end, s.getBreakMinutes() == null ? 0 : s.getBreakMinutes(), s.getStatus());
    }
}