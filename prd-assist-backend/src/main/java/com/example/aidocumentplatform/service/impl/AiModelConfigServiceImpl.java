package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.model.dto.request.AiModelConfigRequest;
import com.example.aidocumentplatform.model.dto.response.AiModelConfigResponse;
import com.example.aidocumentplatform.model.entity.AiModelConfig;
import com.example.aidocumentplatform.repository.AiModelConfigRepository;
import com.example.aidocumentplatform.service.AiModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiModelConfigServiceImpl implements AiModelConfigService {

    private final AiModelConfigRepository repository;

    @Override
    @Transactional(readOnly = true)
    public AiModelConfigResponse get(Long userId) {
        return repository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(this::defaultResponse);
    }

    @Override
    @Transactional
    public AiModelConfigResponse save(Long userId, AiModelConfigRequest request) {
        AiModelConfig config = repository.findByUserId(userId)
                .orElseGet(() -> AiModelConfig.builder().userId(userId).build());
        String nextProvider = normalize(request.getProvider(), "deepseek");
        String nextBaseUrl = trim(request.getBaseUrl());
        boolean nextOpenAiAuthEnabled = request.getOpenAiAuthEnabled() == null || request.getOpenAiAuthEnabled();
        String nextAuthHeaderType = normalizeAuthHeaderType(request.getAuthHeaderType());
        boolean keyRequired = isApiKeyRequired(nextOpenAiAuthEnabled, nextAuthHeaderType);
        boolean hasNewApiKey = request.getApiKey() != null && !request.getApiKey().isBlank();
        boolean providerOrEndpointChanged = config.getId() != null
                && (!normalize(config.getProvider(), "").equals(nextProvider)
                || !trim(config.getBaseUrl()).equals(nextBaseUrl));
        if (keyRequired && providerOrEndpointChanged && !hasNewApiKey) {
            throw new IllegalArgumentException("切换供应商或 Base URL 时请重新填写 API Key，避免沿用旧供应商的 Key");
        }
        if (keyRequired && (config.getApiKey() == null || config.getApiKey().isBlank()) && !hasNewApiKey) {
            throw new IllegalArgumentException("请填写 API Key");
        }

        config.setProvider(nextProvider);
        config.setBaseUrl(nextBaseUrl);
        config.setModel(trim(request.getModel()));
        config.setApiType(normalize(request.getApiType(), "responses"));
        config.setAppendApiPath(request.getAppendApiPath() == null || request.getAppendApiPath());
        config.setOpenAiAuthEnabled(nextOpenAiAuthEnabled);
        config.setAuthHeaderType(nextAuthHeaderType);
        config.setActorAuthorization(trimToNull(request.getActorAuthorization()));
        config.setReasoningEffort(trimToNull(request.getReasoningEffort()));
        config.setDisableResponseStorage(request.getDisableResponseStorage() == null || request.getDisableResponseStorage());
        config.setMaxOutputTokens(request.getMaxOutputTokens() == null ? 16384 : request.getMaxOutputTokens());
        config.setImageDetail(normalize(request.getImageDetail(), "high"));
        config.setEnabled(request.getEnabled() == null || request.getEnabled());

        if (hasNewApiKey) {
            config.setApiKey(request.getApiKey().trim());
        }

        return toResponse(repository.save(config));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiModelConfig> findEnabledByUserId(Long userId) {
        return repository.findByUserId(userId)
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()));
    }

    private AiModelConfigResponse defaultResponse() {
        boolean openAiAuthEnabled = true;
        String authHeaderType = "bearer";
        return AiModelConfigResponse.builder()
                .provider("deepseek")
                .baseUrl("https://api.deepseek.com")
                .model("deepseek-chat")
                .apiType("chat-completions")
                .appendApiPath(true)
                .openAiAuthEnabled(openAiAuthEnabled)
                .authHeaderType(authHeaderType)
                .disableResponseStorage(true)
                .maxOutputTokens(16384)
                .imageDetail("high")
                .enabled(true)
                .hasApiKey(false)
                .apiKeyRequired(isApiKeyRequired(openAiAuthEnabled, authHeaderType))
                .maskedApiKey("")
                .build();
    }

    private AiModelConfigResponse toResponse(AiModelConfig config) {
        String key = config.getApiKey();
        boolean openAiAuthEnabled = Boolean.TRUE.equals(config.getOpenAiAuthEnabled());
        String authHeaderType = normalizeAuthHeaderType(config.getAuthHeaderType());
        return AiModelConfigResponse.builder()
                .id(config.getId())
                .provider(config.getProvider())
                .baseUrl(config.getBaseUrl())
                .maskedApiKey(mask(key))
                .hasApiKey(key != null && !key.isBlank())
                .apiKeyRequired(isApiKeyRequired(openAiAuthEnabled, authHeaderType))
                .model(config.getModel())
                .apiType(config.getApiType())
                .appendApiPath(config.getAppendApiPath())
                .openAiAuthEnabled(config.getOpenAiAuthEnabled())
                .authHeaderType(config.getAuthHeaderType())
                .actorAuthorization(config.getActorAuthorization())
                .reasoningEffort(config.getReasoningEffort())
                .disableResponseStorage(config.getDisableResponseStorage())
                .maxOutputTokens(config.getMaxOutputTokens())
                .imageDetail(config.getImageDetail())
                .enabled(config.getEnabled())
                .build();
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) return "";
        String trimmed = key.trim();
        if (trimmed.length() <= 8) return "****";
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalize(String value, String fallback) {
        String trimmed = trim(value).toLowerCase();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private static String normalizeAuthHeaderType(String value) {
        String trimmed = normalize(value, "bearer");
        return switch (trimmed) {
            case "bearer", "x-api-key", "x-goog-api-key", "none" -> trimmed;
            default -> "bearer";
        };
    }

    private static boolean isApiKeyRequired(boolean openAiAuthEnabled, String authHeaderType) {
        return openAiAuthEnabled && !"none".equals(normalizeAuthHeaderType(authHeaderType));
    }
}