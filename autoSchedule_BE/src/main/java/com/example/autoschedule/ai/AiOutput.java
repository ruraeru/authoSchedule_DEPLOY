package com.example.autoschedule.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Gemini 가 돌려주는 JSON (PRD 8.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiOutput(List<AiShift> shifts, List<AiUnfilled> unfilled, String notes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiShift(Long staffId, String date, String start, String end, String partId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiUnfilled(String date, String partId, Integer shortage, String reason) {}
}