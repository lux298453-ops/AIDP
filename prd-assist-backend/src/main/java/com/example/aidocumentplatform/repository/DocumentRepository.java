package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query(value = """
            SELECT d.id AS id,
                   d.doc_type AS docType,
                   d.title AS title,
                   d.description AS description,
                   d.task_type AS taskType,
                   d.task_id AS taskId,
                   d.prd_document_id AS prdDocumentId,
                   d.created_at AS createdAt
            FROM (
                SELECT p.id,
                       'PRD' AS doc_type,
                       p.title,
                       p.description,
                       'PRD_GENERATE' AS task_type,
                       p.task_id,
                       CAST(NULL AS BIGINT) AS prd_document_id,
                       p.created_at
                FROM prd_document p
                WHERE p.user_id = :userId
                  AND (:taskType IS NULL OR :taskType = '' OR :taskType = 'PRD_GENERATE')
                  AND (:keyword IS NULL OR :keyword = '' OR p.title ILIKE CONCAT('%%', :keyword, '%%'))

                UNION ALL

                SELECT pt.id,
                       'PROTOTYPE' AS doc_type,
                       CONCAT('原型图(', pt.platform, ')') AS title,
                       NULL AS description,
                       'PROTOTYPE' AS task_type,
                       pt.task_id,
                       pt.prd_document_id,
                       pt.created_at
                FROM prototype_result pt
                WHERE pt.user_id = :userId
                  AND (:taskType IS NULL OR :taskType = '' OR :taskType = 'PROTOTYPE')
                  AND (:keyword IS NULL OR :keyword = '' OR CONCAT('原型图(', pt.platform, ')') ILIKE CONCAT('%%', :keyword, '%%'))

                UNION ALL

                SELECT r.id,
                       'REVIEW' AS doc_type,
                       CONCAT('审查报告 #', r.id) AS title,
                       NULL AS description,
                       'PRD_REVIEW' AS task_type,
                       r.task_id,
                       r.prd_document_id,
                       r.created_at
                FROM review_report r
                WHERE r.user_id = :userId
                  AND (:taskType IS NULL OR :taskType = '' OR :taskType = 'PRD_REVIEW')
                  AND (:keyword IS NULL OR :keyword = '' OR CONCAT('审查报告 #', r.id) ILIKE CONCAT('%%', :keyword, '%%'))
            ) d
            ORDER BY d.created_at DESC
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM (
                        SELECT p.id
                        FROM prd_document p
                        WHERE p.user_id = :userId
                          AND (:taskType IS NULL OR :taskType = '' OR :taskType = 'PRD_GENERATE')
                          AND (:keyword IS NULL OR :keyword = '' OR p.title ILIKE CONCAT('%%', :keyword, '%%'))

                        UNION ALL

                        SELECT pt.id
                        FROM prototype_result pt
                        WHERE pt.user_id = :userId
                          AND (:taskType IS NULL OR :taskType = '' OR :taskType = 'PROTOTYPE')
                          AND (:keyword IS NULL OR :keyword = '' OR CONCAT('原型图(', pt.platform, ')') ILIKE CONCAT('%%', :keyword, '%%'))

                        UNION ALL

                        SELECT r.id
                        FROM review_report r
                        WHERE r.user_id = :userId
                          AND (:taskType IS NULL OR :taskType = '' OR :taskType = 'PRD_REVIEW')
                          AND (:keyword IS NULL OR :keyword = '' OR CONCAT('审查报告 #', r.id) ILIKE CONCAT('%%', :keyword, '%%'))
                    ) d
                    """,
            nativeQuery = true)
    Page<DocumentListProjection> findUnifiedDocuments(@Param("userId") Long userId,
                                                      @Param("keyword") String keyword,
                                                      @Param("taskType") String taskType,
                                                      Pageable pageable);
}
