package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.exception.BusinessException;
import com.example.aidocumentplatform.exception.ErrorCode;
import com.example.aidocumentplatform.model.dto.request.TaskCreateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final AsyncTaskRepository asyncTaskRepository;

    // 内存中的 SSE 连接注册表（每任务一个 emitter）
    private final Map<Long, SseEmitter> sseRegistry = new ConcurrentHashMap<>();

    @Override
    public AsyncTask createTask(TaskCreateRequest request, Long userId) {
        AsyncTask task = AsyncTask.builder()
                .userId(userId)
                .taskType(request.getTaskType())
                .status(TaskStatus.PENDING)
                .inputParams(request.getInputData())
                .build();

        return asyncTaskRepository.save(task);
    }

    @Override
    public AsyncTask getTask(Long taskId, Long userId) {
        AsyncTask task = asyncTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        return task;
    }

    @Override
    public List<AsyncTask> listByUser(Long userId) {
        return asyncTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public SseEmitter subscribeTaskProgress(Long taskId, Long userId) {
        AsyncTask task = getTask(taskId, userId);

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 如果任务已经结束，立即发送最终状态并关闭连接
        if (task.getStatus() == TaskStatus.SUCCESS) {
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(Map.of("taskId", taskId, "progress", 100, "message", "任务已完成")));
                emitter.complete();
            } catch (IOException ignored) {}
            return emitter;
        }
        if (task.getStatus() == TaskStatus.FAILED) {
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(Map.of("taskId", taskId, "progress", 0,
                                "message", "任务失败: " + (task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误"))));
                emitter.complete();
            } catch (IOException ignored) {}
            return emitter;
        }

        // 任务还在运行中，注册 emitter 等待后续推送
        sseRegistry.put(taskId, emitter);
        emitter.onCompletion(() -> sseRegistry.remove(taskId));
        emitter.onTimeout(() -> sseRegistry.remove(taskId));
        emitter.onError(e -> sseRegistry.remove(taskId));

        return emitter;
    }

    public void pushProgress(Long taskId, int progress, String message) {
        SseEmitter emitter = sseRegistry.get(taskId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(Map.of("taskId", taskId, "progress", progress, "message", message)));
            } catch (IOException e) {
                sseRegistry.remove(taskId);
                log.warn("SSE推送失败, taskId={}", taskId, e);
            }
            // 终态（成功/失败）推送后关闭 emitter，让前端 onerror 能触发
            if (progress >= 100 || progress < 0) {
                try { emitter.complete(); } catch (Exception ignored) {}
                sseRegistry.remove(taskId);
            }
        } else {
            log.debug("SSE emitter 不存在, taskId={}, progress={}（前端可能已断开或任务已结束）", taskId, progress);
        }
    }
}
