package com.example.aidocumentplatform.model.entity;

import com.example.aidocumentplatform.common.JsonConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "prd_document_id", nullable = false)
    private Long prdDocumentId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Convert(converter = JsonConverter.class)
    @Column(nullable = false, columnDefinition = "JSON")
    private String dimensions;

    @Convert(converter = JsonConverter.class)
    @Column(nullable = false, columnDefinition = "JSON")
    private String issues;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
