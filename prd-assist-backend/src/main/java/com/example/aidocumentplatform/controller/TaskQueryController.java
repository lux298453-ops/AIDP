package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务查询接口（用户要求的路径风格）。
 *
 * GET  /api/task/{taskId}         →  查询任务状态
 * GET  /api/task/{taskId}/stream  →  SSE 实时推送进度
 */
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskQueryController {

    private final TaskService taskService;

    /** 查询任务状态 */
    @GetMapping("/{taskId}")
    public ApiResponse<AsyncTask> getTask(@PathVariable Long taskId) {
        Long userId = getCurrentUserId();
        AsyncTask task = taskService.getTask(taskId, userId);
        return ApiResponse.success(task);
    }

    /** SSE 实时推送任务进度 */
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long taskId) {
        Long userId = getCurrentUserId();
        return taskService.subscribeTaskProgress(taskId, userId);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
