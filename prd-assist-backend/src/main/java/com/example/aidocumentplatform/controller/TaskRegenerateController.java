package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.TaskRegenerateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 重新生成接口。
 *
 * POST /api/task/{taskId}/regenerate  →  基于原任务参数创建新任务，返回 { taskId }
 */
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskRegenerateController {

    private final TaskRegenerateService taskRegenerateService;

    @PostMapping("/{taskId}/regenerate")
    public ApiResponse<Map<String, Long>> regenerate(@PathVariable Long taskId) {
        Long userId = getCurrentUserId();
        Long newTaskId = taskRegenerateService.regenerate(taskId, userId);
        return ApiResponse.success(Map.of("taskId", newTaskId));
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
