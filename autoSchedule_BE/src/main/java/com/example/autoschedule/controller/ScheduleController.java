package com.example.autoschedule.controller;

import com.example.autoschedule.dto.ScheduleDto;
import com.example.autoschedule.dto.ScheduleDto.LeaveBody;
import com.example.autoschedule.dto.ScheduleDto.ShiftBody;
import com.example.autoschedule.dto.ScheduleReportDto;
import com.example.autoschedule.dto.TemplateDto;
import com.example.autoschedule.service.AiScheduleService;
import com.example.autoschedule.service.ReportService;
import com.example.autoschedule.service.TemplateService;
import com.example.autoschedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** api-spec 6장. 인증 필터가 principal 로 넣어 주는 사용자 id 기준으로 "내 매장" 근무표만 다룬다. */
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final AiScheduleService aiScheduleService;
    private final ReportService reportService;
    private final TemplateService templateService;

    @GetMapping
    public List<ScheduleDto.Response> list(@AuthenticationPrincipal Long userId) {
        return scheduleService.list(userId);
    }

    @GetMapping("/{id}")
    public ScheduleDto.Response get(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return scheduleService.get(userId, id);
    }

    @PostMapping
    public ScheduleDto.Response create(@AuthenticationPrincipal Long userId,
                                       @Valid @RequestBody ScheduleDto.CreateRequest request) {
        return scheduleService.create(userId, request);
    }

    /** AI 근무표 생성. 외부 호출이 오래 걸릴 수 있다(시도당 최대 gemini.timeout-seconds). */
    @PostMapping("/generate")
    public ScheduleDto.Response generate(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody ScheduleDto.GenerateRequest request) {
        return aiScheduleService.generate(userId, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        scheduleService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/shifts")
    public ScheduleDto.Response saveAll(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                        @RequestBody ScheduleDto.SaveShiftsRequest request) {
        return scheduleService.saveAll(userId, id, request);
    }

    /** 대시보드 리포트: 주휴수당 대상자 수, 예상 인건비(기본급 + 주휴수당 + 5인 이상 가산수당) */
    @GetMapping("/{id}/report")
    public ScheduleReportDto.Response report(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return reportService.report(userId, id);
    }

    /** 템플릿을 이 근무표의 특정 주(월요일 시작)에 삽입 */
    @PostMapping("/{id}/apply-template")
    public TemplateDto.ApplyWeekResult applyTemplate(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                                     @Valid @RequestBody TemplateDto.ApplyWeekRequest request) {
        return templateService.applyToWeek(userId, id, request);
    }

    @PostMapping("/{id}/confirm")
    public ScheduleDto.Response confirm(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return scheduleService.confirm(userId, id);
    }

    // ----- 개별 근무 블록 (UI_requirements: 블록 추가/수정)

    @PostMapping("/{id}/shifts")
    public ShiftBody addShift(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                              @RequestBody ShiftBody body) {
        return scheduleService.addShift(userId, id, body);
    }

    @PutMapping("/{id}/shifts/{shiftId}")
    public ShiftBody updateShift(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                 @PathVariable String shiftId, @RequestBody ShiftBody body) {
        return scheduleService.updateShift(userId, id, shiftId, body);
    }

    @DeleteMapping("/{id}/shifts/{shiftId}")
    public ResponseEntity<Void> deleteShift(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                            @PathVariable String shiftId) {
        scheduleService.deleteShift(userId, id, shiftId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/leaves")
    public LeaveBody addLeave(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                              @RequestBody LeaveBody body) {
        return scheduleService.addLeave(userId, id, body);
    }
}