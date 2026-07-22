package com.example.aidocumentplatform.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 256)
    private String fileName;

    @Column(nullable = false, length = 32)
    private String fileType;

    @Column(length = 512)
    private String filePath;

    @Column(nullable = false)
    private Long fileSize;

    @Column(columnDefinition = "JSONB")
    private String parsedContent;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
