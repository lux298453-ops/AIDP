package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.TaskCreateRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.dto.response.PagedResponse;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    public ApiResponse<PagedResponse<AsyncTask>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = getCurrentUserId();
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AsyncTask> result = taskService.listByUser(userId, pageable);
        PagedResponse<AsyncTask> paged = PagedResponse.<AsyncTask>builder()
                .content(result.getContent())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .number(result.getNumber())
                .size(result.getSize())
                .build();
        return ApiResponse.success(paged);
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