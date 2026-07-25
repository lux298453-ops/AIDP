package com.example.aidocumentplatform.model.entity;

import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "prototype_result")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrototypeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "prd_document_id")
    private Long prdDocumentId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prototype_type", nullable = false, length = 20)
    private PrototypeType prototypeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Platform platform;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "reference_image_url", length = 255)
    private String referenceImageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.platform == null) {
            this.platform = Platform.APP;
        }
    }
}
