package com.example.autoschedule.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * schema.sql: worker_availability
 * 알바생: (요일, part) 한 행이 "그 요일에 근무 가능한 파트" 하나. 직원: (요일, start/end) 한 행이 그 요일의 고정 출퇴근.
 */
@Entity
@Table(name = "worker_availability")
@Getter
@Setter
@NoArgsConstructor
public class WorkerAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    private Weekday dayOfWeek;

    /** 파트 삭제 시 DB가 SET NULL 하므로 서비스에서 part 와 시간이 모두 비면 행을 정리해야 한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id")
    private Part part;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;
}