package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.PrototypeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrototypeResultRepository extends JpaRepository<PrototypeResult, Long> {

    List<PrototypeResult> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<PrototypeResult> findByTaskId(Long taskId);

    List<PrototypeResult> findByPrdDocumentId(Long prdDocumentId);
}
