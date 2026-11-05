package com.example.autoschedule.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/** schema.sql: store_hours - 매장 요일별 영업시간 */
@Entity
@Table(name = "store_hours")
@Getter
@Setter
@NoArgsConstructor
public class StoreHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    private Weekday dayOfWeek;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    /** 24:00 은 LocalTime 으로 표현할 수 없으므로 00:00(자정)으로 저장하고 서비스에서 해석한다. */
    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;
}