package com.example.autoschedule.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** schema.sql: workers - 직원(FULL_TIME) / 알바생(PART_TIME) */
@Entity
@Table(name = "workers")
@Getter
@Setter
@NoArgsConstructor
public class Worker {

    public enum EmploymentType { FULL_TIME, PART_TIME }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false)
    private EmploymentType employmentType;

    @Column(length = 50)
    private String title;

    @Column(name = "hourly_wage", nullable = false)
    private Integer hourlyWage;

    @Column(length = 20)
    private String phone;

    /** 연소근로자(만 18세 미만) */
    @Column(name = "is_minor")
    private boolean minor = false;

    /** 직원용 주 소정근로시간 (예: 40) */
    @Column(name = "contract_weekly_hours")
    private Integer contractWeeklyHours;

    /** 알바생용 희망 최소 주간 시간 */
    @Column(name = "min_weekly_hours")
    private Integer minWeeklyHours;

    /** 알바생용 희망 최대 주간 시간 */
    @Column(name = "max_weekly_hours")
    private Integer maxWeeklyHours;

    @OneToMany(mappedBy = "worker", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkerAvailability> availability = new ArrayList<>();

    public boolean isFullTime() {
        return employmentType == EmploymentType.FULL_TIME;
    }
}