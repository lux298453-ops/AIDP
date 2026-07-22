package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.PrdDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrdDocumentRepository extends JpaRepository<PrdDocument, Long> {

    List<PrdDocument> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<PrdDocument> findByTaskId(Long taskId);
}
