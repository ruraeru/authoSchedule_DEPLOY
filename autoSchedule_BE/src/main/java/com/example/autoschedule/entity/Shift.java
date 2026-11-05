package com.example.autoschedule.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/** schema.sql: shifts - 개별 근무 블록 (근무/미정/연차/휴무) */
@Entity
@Table(name = "shifts")
@Getter
@Setter
@NoArgsConstructor
public class Shift {

    /** NORMAL 근무 / TENTATIVE 시간 미정 / ANNUAL_LEAVE 연차(유급 8h) / DAY_OFF 휴무 */
    public enum Status { NORMAL, TENTATIVE, ANNUAL_LEAVE, DAY_OFF }

    /** 클라이언트가 만든 UUID. 없으면 저장 직전에 서버가 채운다. */
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    /** 알바생 파트 근무면 파트, 직원 유동 근무면 null (파트 삭제 시 DB가 SET NULL) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id")
    private Part part;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "break_minutes")
    private Integer breakMinutes = 0;

    @Column(name = "break_start_time")
    private LocalTime breakStartTime;

    @Enumerated(EnumType.STRING)
    private Status status = Status.NORMAL;

    @PrePersist
    void assignId() {
        if (id == null) id = java.util.UUID.randomUUID().toString();
    }
}