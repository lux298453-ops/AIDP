package com.example.aidocumentplatform.model.entity;

import com.example.aidocumentplatform.common.JsonConverter;
import com.example.aidocumentplatform.model.enums.DetailLevel;
import com.example.aidocumentplatform.model.enums.DocumentSourceType;
import com.example.aidocumentplatform.model.enums.TemplateType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "prd_document")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrdDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(nullable = false, length = 50)
    private String title;

    @Column(length = 500)
    private String description;

    @Convert(converter = JsonConverter.class)
    @Column(nullable = false, columnDefinition = "JSON")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private DocumentSourceType sourceType;

    @Column(name = "xmind_file_url", length = 255)
    private String xmindFileUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TemplateType template;

    @Enumerated(EnumType.STRING)
    @Column(name = "detail_level", nullable = false, length = 20)
    private DetailLevel detailLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.sourceType == null) {
            this.sourceType = DocumentSourceType.MANUAL;
        }
        if (this.template == null) {
            this.template = TemplateType.STANDARD;
        }
        if (this.detailLevel == null) {
            this.detailLevel = DetailLevel.DETAILED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
