package com.example.autoschedule.controller;

import com.example.autoschedule.dto.ScheduleDto;
import com.example.autoschedule.dto.TemplateDto;
import com.example.autoschedule.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** api-spec 7장 */
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public List<TemplateDto.Response> list(@AuthenticationPrincipal Long userId) {
        return templateService.list(userId);
    }

    @PostMapping
    public TemplateDto.Response save(@AuthenticationPrincipal Long userId,
                                     @Valid @RequestBody TemplateDto.SaveRequest request) {
        return templateService.save(userId, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        templateService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/apply")
    public ScheduleDto.Response apply(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                      @Valid @RequestBody TemplateDto.ApplyRequest request) {
        return templateService.applyToMonth(userId, id, request);
    }
}