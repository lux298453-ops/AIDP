package com.example.aidocumentplatform.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiModelConfigResponse {

    private Long id;
    private String provider;
    private String baseUrl;
    private String maskedApiKey;
    private Boolean hasApiKey;
    private Boolean apiKeyRequired;
    private String model;
    private String apiType;
    private Boolean appendApiPath;
    private Boolean openAiAuthEnabled;
    private String authHeaderType;
    private String actorAuthorization;
    private String reasoningEffort;
    private Boolean disableResponseStorage;
    private Integer maxOutputTokens;
    private String imageDetail;
    private Boolean enabled;
}