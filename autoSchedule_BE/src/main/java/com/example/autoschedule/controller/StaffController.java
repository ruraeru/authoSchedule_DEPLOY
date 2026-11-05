package com.example.autoschedule.controller;

import com.example.autoschedule.dto.StaffDto;
import com.example.autoschedule.service.StaffService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public List<StaffDto.Response> list(@AuthenticationPrincipal Long userId) {
        return staffService.list(userId);
    }

    @PostMapping
    public ResponseEntity<StaffDto.Response> create(@AuthenticationPrincipal Long userId,
                                                    @Valid @RequestBody StaffDto.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staffService.create(userId, request));
    }

    @PutMapping("/{id}")
    public StaffDto.Response update(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                                    @Valid @RequestBody StaffDto.Request request) {
        return staffService.update(userId, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        staffService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
