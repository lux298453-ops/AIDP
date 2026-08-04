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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final IdempotentTaskService idempotentTaskService;

    @Value("${app.sse.emitter-timeout-ms:1800000}")
    private long sseEmitterTimeoutMs;

    private final Map<Long, SseEmitter> sseRegistry = new ConcurrentHashMap<>();
    private final Map<Long, ProgressSnapshot> progressSnapshots = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> contentBuffers = new ConcurrentHashMap<>();
    private final Map<Long, List<BufferedSseEvent>> customEventBuffers = new ConcurrentHashMap<>();

    @Override
    public AsyncTask createTask(TaskCreateRequest request, Long userId) {
        return idempotentTaskService.createOrReuseTask(userId, request.getTaskType(), request.getInputData()).task();
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
    public Page<AsyncTask> listByUser(Long userId, Pageable pageable) {
        return asyncTaskRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    public SseEmitter subscribeTaskProgress(Long taskId, Long userId) {
        AsyncTask task = getTask(taskId, userId);
        SseEmitter emitter = new SseEmitter(sseEmitterTimeoutMs);
        ProgressSnapshot snapshot = progressSnapshots.get(taskId);

        if (task.getStatus() == TaskStatus.SUCCESS) {
            replayCustomEvents(emitter, taskId);
            safeSendAndComplete(emitter, taskId, 100, terminalMessage(task, snapshot, "任务已完成"));
            cleanupTaskBuffers(taskId, true);
            return emitter;
        }
        if (task.getStatus() == TaskStatus.FAILED) {
            String fallback = "任务失败: " + (task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误");
            safeSendAndComplete(emitter, taskId, 0, terminalMessage(task, snapshot, fallback));
            cleanupTaskBuffers(taskId, true);
            return emitter;
        }

        sseRegistry.put(taskId, emitter);
        emitter.onCompletion(() -> sseRegistry.remove(taskId));
        emitter.onTimeout(() -> {
            sseRegistry.remove(taskId);
            log.info("SSE timeout, taskId={}", taskId);
        });
        emitter.onError(e -> {
            sseRegistry.remove(taskId);
            log.warn("SSE connection error, taskId={}, cause={}", taskId, e.toString());
        });

        replayProgressSnapshot(emitter, taskId, snapshot);

        StringBuilder buffered = contentBuffers.get(taskId);
        if (buffered != null && !buffered.isEmpty()) {
            sendContentEvent(taskId, buffered.toString(), true);
        }
        replayCustomEvents(taskId);

        return emitter;
    }

    public void pushProgress(Long taskId, int progress, String message) {
        progressSnapshots.put(taskId, new ProgressSnapshot(progress, message));
        SseEmitter emitter = sseRegistry.get(taskId);
        if (emitter == null) {
            log.debug("SSE emitter missing, taskId={}, progress={}", taskId, progress);
            if (progress >= 100 || progress <= 0) {
                cleanupTaskBuffers(taskId, false);
            }
            return;
        }

        if (!sendProgressEvent(emitter, taskId, progress, message)) {
            sseRegistry.remove(taskId);
            return;
        }

        if (progress >= 100 || progress <= 0) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            sseRegistry.remove(taskId);
            cleanupTaskBuffers(taskId, true);
        }
    }

    public void pushContentDelta(Long taskId, String delta) {
        if (delta == null || delta.isBlank()) return;
        contentBuffers.compute(taskId, (id, buffer) -> {
            StringBuilder next = buffer == null ? new StringBuilder() : buffer;
            next.append(delta);
            return next;
        });
        sendContentEvent(taskId, delta, false);
    }

    private void sendContentEvent(Long taskId, String delta, boolean snapshot) {
        SseEmitter emitter = sseRegistry.get(taskId);
        if (emitter == null) {
            log.debug("SSE emitter missing, taskId={}, contentLen={}", taskId, delta.length());
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("content")
                    .data(Map.of("taskId", taskId, "delta", delta, "snapshot", snapshot)));
        } catch (Exception e) {
            sseRegistry.remove(taskId);
            log.warn("SSE content push failed, taskId={}, cause={}", taskId, e.toString());
        }
    }

    public void pushCustomEvent(Long taskId, String eventName, Object data) {
        if (taskId == null || eventName == null || eventName.isBlank() || data == null) return;
        customEventBuffers
                .computeIfAbsent(taskId, id -> Collections.synchronizedList(new ArrayList<>()))
                .add(new BufferedSseEvent(eventName, data));
        sendCustomEvent(taskId, eventName, data);
    }

    private void sendCustomEvent(Long taskId, String eventName, Object data) {
        SseEmitter emitter = sseRegistry.get(taskId);
        if (emitter == null) {
            log.debug("SSE emitter missing, taskId={}, event={}", taskId, eventName);
            return;
        }
        sendCustomEvent(emitter, taskId, eventName, data);
    }

    private void sendCustomEvent(SseEmitter emitter, Long taskId, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            sseRegistry.remove(taskId);
            log.warn("SSE custom event push failed, taskId={}, event={}, cause={}",
                    taskId, eventName, e.toString());
        }
    }

    private void replayCustomEvents(Long taskId) {
        List<BufferedSseEvent> customEvents = customEventBuffers.get(taskId);
        if (customEvents == null || customEvents.isEmpty()) return;
        synchronized (customEvents) {
            for (BufferedSseEvent event : customEvents) {
                sendCustomEvent(taskId, event.name(), event.data());
            }
        }
    }

    private void replayCustomEvents(SseEmitter emitter, Long taskId) {
        List<BufferedSseEvent> customEvents = customEventBuffers.get(taskId);
        if (customEvents == null || customEvents.isEmpty()) return;
        synchronized (customEvents) {
            for (BufferedSseEvent event : customEvents) {
                sendCustomEvent(emitter, taskId, event.name(), event.data());
            }
        }
    }

    private void replayProgressSnapshot(SseEmitter emitter, Long taskId, ProgressSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        sendProgressEvent(emitter, taskId, snapshot.progress(), snapshot.message());
    }

    private boolean sendProgressEvent(SseEmitter emitter, Long taskId, int progress, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(Map.of("taskId", taskId, "progress", progress, "message", message)));
            return true;
        } catch (Exception e) {
            log.warn("SSE progress push failed, taskId={}, progress={}, cause={}",
                    taskId, progress, e.toString());
            return false;
        }
    }

    private void safeSendAndComplete(SseEmitter emitter, Long taskId, int progress, String message) {
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(Map.of("taskId", taskId, "progress", progress, "message", message)));
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE terminal push failed, taskId={}, cause={}", taskId, e.toString());
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private String terminalMessage(AsyncTask task, ProgressSnapshot snapshot, String fallback) {
        if (snapshot != null && snapshot.message() != null && !snapshot.message().isBlank()) {
            return snapshot.message();
        }
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            return task.getStatus() == TaskStatus.FAILED ? "任务失败: " + task.getErrorMessage() : fallback;
        }
        return fallback;
    }

    private void cleanupTaskBuffers(Long taskId, boolean clearProgressSnapshot) {
        contentBuffers.remove(taskId);
        customEventBuffers.remove(taskId);
        if (clearProgressSnapshot) {
            progressSnapshots.remove(taskId);
        }
    }

    private record BufferedSseEvent(String name, Object data) {
    }

    private record ProgressSnapshot(int progress, String message) {
    }
}