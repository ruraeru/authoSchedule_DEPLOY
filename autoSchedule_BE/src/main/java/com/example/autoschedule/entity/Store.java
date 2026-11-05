package com.example.autoschedule.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** schema.sql: stores */
@Entity
@Table(name = "stores")
@Getter
@Setter
@NoArgsConstructor
public class Store {

    /** 5인 미만 / 5인 이상 사업장 */
    public enum Scale { UNDER_5, OVER_5 }

    /** 규모 설정. AUTO 면 등록 구성원 수로 scale 을 정한다(프론트 sizeOf 와 같은 규칙). */
    public enum SizeMode { AUTO, UNDER_5, OVER_5 }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    /** 지점명 */
    @Column(length = 100)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scale scale;

    /** null 은 예전 데이터: AUTO 로 본다. scale 은 이 값으로 정한 실제 적용 규모다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "size_mode")
    private SizeMode sizeMode;

    @Column(name = "minimum_wage", nullable = false)
    private Integer minimumWage;

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StoreHours> hours = new ArrayList<>();

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StoreDemand> demand = new ArrayList<>();

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Part> parts = new ArrayList<>();
}