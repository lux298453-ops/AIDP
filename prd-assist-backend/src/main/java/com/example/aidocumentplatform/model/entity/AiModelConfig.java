package com.example.aidocumentplatform.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_model_config", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ai_model_config_user_id", columnNames = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "api_key", length = 1000)
    private String apiKey;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "api_type", nullable = false, length = 40)
    private String apiType;

    @Column(name = "append_api_path", nullable = false)
    private Boolean appendApiPath;

    @Column(name = "openai_auth_enabled", nullable = false)
    private Boolean openAiAuthEnabled;

    @Column(name = "auth_header_type", length = 30)
    private String authHeaderType;

    @Column(name = "actor_authorization", length = 500)
    private String actorAuthorization;

    @Column(name = "reasoning_effort", length = 20)
    private String reasoningEffort;

    @Column(name = "disable_response_storage", nullable = false)
    private Boolean disableResponseStorage;

    @Column(name = "max_output_tokens", nullable = false)
    private Integer maxOutputTokens;

    @Column(name = "image_detail", nullable = false, length = 20)
    private String imageDetail;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        applyDefaults();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        applyDefaults();
    }

    private void applyDefaults() {
        if (provider == null || provider.isBlank()) provider = "openai";
        if (apiType == null || apiType.isBlank()) apiType = "responses";
        if (appendApiPath == null) appendApiPath = true;
        if (openAiAuthEnabled == null) openAiAuthEnabled = true;
        if (authHeaderType == null || authHeaderType.isBlank()) authHeaderType = "bearer";
        if (disableResponseStorage == null) disableResponseStorage = true;
        if (maxOutputTokens == null || maxOutputTokens <= 0) maxOutputTokens = 16384;
        if (imageDetail == null || imageDetail.isBlank()) imageDetail = "high";
        if (enabled == null) enabled = true;
    }
}
