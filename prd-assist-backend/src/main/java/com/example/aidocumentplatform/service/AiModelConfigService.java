package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.AiModelConfigRequest;
import com.example.aidocumentplatform.model.dto.response.AiModelConfigResponse;
import com.example.aidocumentplatform.model.entity.AiModelConfig;

import java.util.Optional;

public interface AiModelConfigService {

    AiModelConfigResponse get(Long userId);

    AiModelConfigResponse save(Long userId, AiModelConfigRequest request);

    Optional<AiModelConfig> findEnabledByUserId(Long userId);
}
