package com.example.autoschedule.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** schema.sql: schedules - 매장별 월간 근무표 */
@Entity
@Table(name = "schedules")
@Getter
@Setter
@NoArgsConstructor
public class Schedule {

    public enum Status { DRAFT, CONFIRMED }

    public enum GenerationType { MANUAL, AI, TEMPLATE }

    /** 주휴수당 정책: 최소화 / 허용 */
    public enum WeeklyHolidayPay { MINIMIZE, ALLOW }

    /** 우선순위: 공평 배분 / 인건비 절감 */
    public enum Priority { FAIRNESS, COST_SAVING }

    @Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 예: "2026-11" */
    @Column(name = "target_year_month", nullable = false, length = 7)
    private String targetYearMonth;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_type", nullable = false)
    private GenerationType generationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "weekly_holiday_pay")
    private WeeklyHolidayPay weeklyHolidayPay;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Column(name = "ai_comment", columnDefinition = "TEXT")
    private String aiComment;

    @Column(name = "total_work_hours")
    private Integer totalWorkHours = 0;

    @Column(name = "expected_labor_cost")
    private Integer expectedLaborCost = 0;

    @Column(name = "holiday_pay_target_count")
    private Integer holidayPayTargetCount = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Shift> shifts = new ArrayList<>();

    public void addShift(Shift shift) {
        shift.setSchedule(this);
        shifts.add(shift);
    }
}