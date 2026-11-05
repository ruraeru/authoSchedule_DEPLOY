package com.example.autoschedule.dto;

import com.example.autoschedule.entity.Worker.EmploymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 프론트(autoSchedule)의 Staff 형태 그대로 주고받는다. 요일 배열은 0=월 ... 6=일.
 * 알바생은 availableParts(요일별 가능 파트 id)·preferredWeeklyHours, 직원은 workHours(요일별 고정 근무)·contractWeeklyHours 를 쓴다.
 */
public class StaffDto {

    public record WorkTime(
            @NotNull @Pattern(regexp = StoreDto.START) String start,
            @NotNull @Pattern(regexp = StoreDto.END) String end) {}

    public record Request(
            @NotBlank @Size(max = 50) String name,
            @NotNull EmploymentType employmentType,
            @Size(max = 50) String title,
            @NotNull @Positive Integer hourlyWage,
            Boolean isMinor,
            @Min(0) Integer contractWeeklyHours,
            List<@NotNull @Min(0) Integer> preferredWeeklyHours,
            List<List<String>> availableParts,
            List<@Valid WorkTime> workHours,
            @Size(max = 20) String phone) {}

    public record Response(
            Long id,
            String name,
            EmploymentType employmentType,
            String title,
            Integer hourlyWage,
            Boolean isMinor,
            Integer contractWeeklyHours,
            List<Integer> preferredWeeklyHours,
            List<List<String>> availableParts,
            List<WorkTime> workHours,
            String phone) {}
}
