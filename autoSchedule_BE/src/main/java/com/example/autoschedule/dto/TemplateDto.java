package com.example.autoschedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/** api-spec 7장 Template. 날짜 대신 dayIndex(0=월)를 쓴다. templates.shifts_json 에는 {@link Body} 가 JSON 으로 저장된다. */
public class TemplateDto {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShiftItem(int dayIndex, Long staffId, String start, String end, String partId,
                            String breakStart, int breakMinutes, boolean tentative) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeaveItem(int dayIndex, Long staffId, String type) {}

    /** shifts_json 에 저장되는 형태 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(List<ShiftItem> shifts, List<LeaveItem> leaves) {}

    @Getter
    @Setter
    @NoArgsConstructor
    public static class SaveRequest {
        @NotBlank
        @Size(max = 100)
        private String name;
        @NotNull
        private Long scheduleId;
        /** 저장할 주의 월요일. 없으면 그 달의 첫 번째 온전한 주. */
        private LocalDate weekStart;
    }

    /** POST /templates/{id}/apply - 한 달에 적용 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ApplyRequest {
        @NotNull
        private LocalDate periodStart;
    }

    /** POST /schedules/{id}/apply-template - 기존 근무표의 한 주에 적용 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class ApplyWeekRequest {
        @NotNull
        private Long templateId;
        /** 적용할 주의 월요일 */
        @NotNull
        private LocalDate weekStart;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String name;
        private String createdAt;
        private List<ShiftItem> shifts;
        private List<LeaveItem> leaves;
    }

    @Getter
    @AllArgsConstructor
    public static class ApplyWeekResult {
        private ScheduleDto.Response schedule;
        private List<String> warnings;
    }
}