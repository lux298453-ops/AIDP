package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.AsyncTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AsyncTaskRepository extends JpaRepository<AsyncTask, Long> {

    List<AsyncTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AsyncTask> findByUserIdAndStatus(Long userId, String status);
}
