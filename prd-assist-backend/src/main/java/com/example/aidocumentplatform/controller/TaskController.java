package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.TaskCreateRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public ApiResponse<AsyncTask> createTask(@Valid @RequestBody TaskCreateRequest request) {
        Long userId = getCurrentUserId();
        AsyncTask task = taskService.createTask(request, userId);
        return ApiResponse.success(task);
    }

    @GetMapping
    public ApiResponse<List<AsyncTask>> list() {
        Long userId = getCurrentUserId();
        List<AsyncTask> tasks = taskService.listByUser(userId);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/{id}")
    public ApiResponse<AsyncTask> getTask(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        AsyncTask task = taskService.getTask(id, userId);
        return ApiResponse.success(task);
    }

    @GetMapping(value = "/{id}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        return taskService.subscribeTaskProgress(id, userId);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
