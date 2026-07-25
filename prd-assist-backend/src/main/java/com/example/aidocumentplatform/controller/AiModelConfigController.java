package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.AiModelConfigRequest;
import com.example.aidocumentplatform.model.dto.response.AiModelConfigResponse;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.AiModelConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
public class AiModelConfigController {

    private final AiModelConfigService service;

    @GetMapping
    public ApiResponse<AiModelConfigResponse> get() {
        return ApiResponse.success(service.get(getCurrentUserId()));
    }

    @PutMapping
    public ApiResponse<AiModelConfigResponse> save(@Valid @RequestBody AiModelConfigRequest request) {
        return ApiResponse.success(service.save(getCurrentUserId(), request));
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
