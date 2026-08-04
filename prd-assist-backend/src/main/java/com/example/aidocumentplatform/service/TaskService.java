package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.TaskCreateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface TaskService {

    AsyncTask createTask(TaskCreateRequest request, Long userId);

    AsyncTask getTask(Long taskId, Long userId);

    Page<AsyncTask> listByUser(Long userId, Pageable pageable);

    SseEmitter subscribeTaskProgress(Long taskId, Long userId);

    void pushProgress(Long taskId, int progress, String message);

    void pushContentDelta(Long taskId, String delta);

    void pushCustomEvent(Long taskId, String eventName, Object data);
}