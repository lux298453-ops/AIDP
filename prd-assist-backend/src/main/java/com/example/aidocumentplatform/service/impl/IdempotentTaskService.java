package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentTaskService {

    private static final EnumSet<TaskStatus> REUSABLE_STATUSES =
            EnumSet.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.SUCCESS);

    private static final long DEDUP_WINDOW_SECONDS = 120;

    private final AsyncTaskRepository asyncTaskRepository;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Transactional
    public TaskReservation createOrReuseTask(Long userId, TaskType taskType, String inputParams) {
        String safeInput = inputParams == null ? "" : inputParams;
        String lockKey = userId + ":" + taskType.name() + ":" + Integer.toHexString(safeInput.hashCode());
        ReentrantLock lock = locks.computeIfAbsent(lockKey, key -> new ReentrantLock());
        lock.lock();
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(DEDUP_WINDOW_SECONDS);
            AsyncTask existing = asyncTaskRepository
                    .findFirstByUserIdAndTaskTypeAndInputParamsAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                            userId, taskType, safeInput, REUSABLE_STATUSES, cutoff)
                    .orElse(null);
            if (existing != null) {
                log.info("Reusing recent async task: taskId={}, userId={}, taskType={}",
                        existing.getId(), userId, taskType);
                return new TaskReservation(existing, false);
            }

            AsyncTask task = AsyncTask.builder()
                    .userId(userId)
                    .taskType(taskType)
                    .status(TaskStatus.PENDING)
                    .inputParams(safeInput)
                    .build();
            return new TaskReservation(asyncTaskRepository.save(task), true);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(lockKey, lock);
            }
        }
    }

    public record TaskReservation(AsyncTask task, boolean created) {
    }
}
