package com.example.autoschedule.dto;

import com.example.autoschedule.entity.Store;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 프론트(autoSchedule)의 Store 형태 그대로 주고받는다. 시각은 "HH:MM", 요일 배열은 0=월 ... 6=일.
 * 서버는 이 묶음을 stores / store_hours / parts / store_demand 테이블로 풀어서 저장한다.
 */
public class StoreDto {

    /** 시작 시각: 00:00~23:59 */
    public static final String START = "^([01]\\d|2[0-3]):[0-5]\\d$";
    /** 종료 시각: 24시를 넘기는 새벽 영업은 26:00 처럼 보낸다(최대 47:59). 자세한 규칙은 TimeCodec 참고. */
    public static final String END = "^([0-3]\\d|4[0-7]):[0-5]\\d$";

    /**
     * tone 은 parts 테이블에 컬럼이 없어 저장하지 않는다. 응답에서는 시작 시각 순서로 정해 돌려준다.
     * required 는 이 파트 시간대의 필요 인원(없으면 1).
     */
    public record PartItem(
            String id,
            @NotBlank @Size(max = 50) String name,
            String tone,
            @NotNull @Pattern(regexp = START) String start,
            @NotNull @Pattern(regexp = END) String end,
            @Min(0) Integer breakMinutes,
            @Min(0) Integer required) {}

    /** days 가 없으면 매일 적용한다. */
    public record DemandItem(
            @NotNull @Pattern(regexp = START) String start,
            @NotNull @Pattern(regexp = END) String end,
            @NotNull @Min(0) Integer required,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<@NotNull @Min(0) @Max(6) Integer> days) {}

    /**
     * sizeMode 가 있으면 그것으로 규모를 정하고(size 는 무시), 없으면 size 를 수동 설정으로 본다.
     * demand 가 없으면 파트별 required 에서 만든다(프론트는 파트별 필요 인원만 보낸다).
     */
    public record Request(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String branch,
            Store.SizeMode sizeMode,
            Store.Scale size,
            @NotNull @Pattern(regexp = START) String open,
            @NotNull @Pattern(regexp = END) String close,
            @NotNull @Pattern(regexp = START) String weekendOpen,
            @NotNull @Pattern(regexp = END) String weekendClose,
            @NotNull @Valid List<PartItem> parts,
            @Valid List<DemandItem> demand,
            @NotNull @Positive Integer minimumWage) {}

    public record Response(
            Long id,
            String name,
            String branch,
            Store.SizeMode sizeMode,
            Store.Scale size,
            String open,
            String close,
            String weekendOpen,
            String weekendClose,
            List<PartItem> parts,
            List<DemandItem> demand,
            Integer minimumWage) {}
}
