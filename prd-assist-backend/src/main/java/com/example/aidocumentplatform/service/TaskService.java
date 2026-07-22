package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.TaskCreateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface TaskService {

    AsyncTask createTask(TaskCreateRequest request, Long userId);

    AsyncTask getTask(Long taskId, Long userId);

    List<AsyncTask> listByUser(Long userId);

    SseEmitter subscribeTaskProgress(Long taskId, Long userId);
}
