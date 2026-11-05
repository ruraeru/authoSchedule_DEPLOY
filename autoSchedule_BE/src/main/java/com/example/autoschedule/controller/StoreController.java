package com.example.autoschedule.controller;

import com.example.autoschedule.dto.StoreDto;
import com.example.autoschedule.service.StoreService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stores/me")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    /** 매장이 없는 사용자에게는 기본 매장을 만들어 돌려준다. */
    @GetMapping
    public StoreDto.Response get(@AuthenticationPrincipal Long userId) {
        return storeService.get(userId);
    }

    /** 매장 설정 화면의 저장: 매장, 영업시간, 근무 파트, 필요 인원을 한 번에 저장한다. */
    @PutMapping
    public StoreDto.Response save(@AuthenticationPrincipal Long userId, @Valid @RequestBody StoreDto.Request request) {
        return storeService.save(userId, request);
    }
}
