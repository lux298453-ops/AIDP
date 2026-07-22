package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 原型生成接口。
 *
 * POST /api/prototype/generate  →  提交生成请求，返回 { taskId }
 * GET  /api/prototype/{id}      →  获取原型 HTML 内容
 */
@RestController
@RequestMapping("/api/prototype")
@RequiredArgsConstructor
public class PrototypeGenerateController {

    private final PrototypeGenerateService prototypeGenerateService;
    private final PrototypeResultRepository prototypeResultRepository;

    @PostMapping("/generate")
    public ApiResponse<Map<String, Long>> generate(@Valid @RequestBody PrototypeGenerateRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prototypeGenerateService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /** 获取原型 HTML 内容（用于 iframe 渲染） */
    @GetMapping("/{id}")
    public ApiResponse<PrototypeResult> getPrototype(@PathVariable Long id) {
        PrototypeResult proto = prototypeResultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("原型不存在"));
        return ApiResponse.success(proto);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
