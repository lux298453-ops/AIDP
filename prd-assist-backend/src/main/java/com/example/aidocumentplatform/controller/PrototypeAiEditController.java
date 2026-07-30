package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.PrototypeAiEditRequest;
import com.example.aidocumentplatform.model.dto.request.PrototypeAiEditSnapshotRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.dto.response.PrototypeAiEditResponse;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrototypeAiEditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 原型 AI 辅助修改接口。
 *
 * POST /api/prototype/{id}/ai-edit  →  按自然语言指令修改原型，返回新 HTML 与改动说明
 */
@RestController
@RequestMapping("/api/prototype")
@RequiredArgsConstructor
public class PrototypeAiEditController {

    private final PrototypeAiEditService prototypeAiEditService;

    @PostMapping("/{id}/ai-edit")
    public ApiResponse<PrototypeAiEditResponse> aiEdit(@PathVariable Long id,
                                                       @Valid @RequestBody PrototypeAiEditRequest request) {
        PrototypeAiEditResponse data = prototypeAiEditService.edit(id, getCurrentUserId(), request);
        return ApiResponse.success(data);
    }

    @PostMapping("/{id}/ai-edit/stream")
    public ApiResponse<Long> aiEditStream(@PathVariable Long id,
                                          @Valid @RequestBody PrototypeAiEditRequest request) {
        Long taskId = prototypeAiEditService.submitStreamEdit(id, getCurrentUserId(), request);
        return ApiResponse.success(taskId);
    }

    @PostMapping("/{id}/ai-edit/snapshot")
    public ApiResponse<PrototypeAiEditResponse> saveAiEditSnapshot(
            @PathVariable Long id,
            @Valid @RequestBody PrototypeAiEditSnapshotRequest request) {
        PrototypeAiEditResponse data = prototypeAiEditService.saveSnapshot(id, getCurrentUserId(), request);
        return ApiResponse.success(data);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
