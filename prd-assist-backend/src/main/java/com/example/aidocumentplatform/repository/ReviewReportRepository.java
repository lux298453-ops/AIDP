package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {

    List<ReviewReport> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ReviewReport> findByPrdDocumentId(Long prdDocumentId);

    List<ReviewReport> findByTaskId(Long taskId);
}
