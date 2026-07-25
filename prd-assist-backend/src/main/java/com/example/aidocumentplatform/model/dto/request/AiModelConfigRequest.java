package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiModelConfigRequest {

    @NotBlank
    private String provider;

    @NotBlank
    private String baseUrl;

    private String apiKey;

    @NotBlank
    private String model;

    @NotBlank
    private String apiType;

    private Boolean appendApiPath;

    private Boolean openAiAuthEnabled;

    private String authHeaderType;

    private String actorAuthorization;

    private String reasoningEffort;

    private Boolean disableResponseStorage;

    @Min(1024)
    @Max(100000)
    private Integer maxOutputTokens;

    private String imageDetail;

    private Boolean enabled;
}
