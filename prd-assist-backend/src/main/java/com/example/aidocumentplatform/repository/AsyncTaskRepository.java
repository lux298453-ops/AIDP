package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AsyncTaskRepository extends JpaRepository<AsyncTask, Long> {

    Page<AsyncTask> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<AsyncTask> findByUserIdAndStatus(Long userId, String status);

    Optional<AsyncTask> findFirstByUserIdAndTaskTypeAndInputParamsAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId,
            TaskType taskType,
            String inputParams,
            Collection<TaskStatus> statuses,
            LocalDateTime createdAtAfter
    );
}