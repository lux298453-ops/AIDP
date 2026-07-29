package com.example.aidocumentplatform.ai.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.model.entity.AiModelConfig;
import com.example.aidocumentplatform.service.AiModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
@Profile("openai")
public class OpenAiClient implements AiClient {

    private final WebClient webClient;
    private final AiModelConfigService aiModelConfigService;
    private final AiCallConfig defaultConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public OpenAiClient(WebClient webClient,
                        AiModelConfigService aiModelConfigService,
                        @Value("${app.ai.provider:openai}") String provider,
                        @Value("${app.ai.api-url}") String apiUrl,
                        @Value("${app.ai.api-key}") String apiKey,
                        @Value("${app.ai.model:gpt-5.1}") String model,
                        @Value("${app.ai.max-output-tokens:16384}") int maxOutputTokens,
                        @Value("${app.ai.image-detail:high}") String imageDetail,
                        @Value("${app.ai.api-type:auto}") String apiType,
                        @Value("${app.ai.fallback-api-type:off}") String fallbackApiType,
                        @Value("${app.ai.append-api-path:true}") boolean appendApiPath,
                        @Value("${app.ai.openai-auth-enabled:true}") boolean openAiAuthEnabled,
                        @Value("${app.ai.auth-header-type:bearer}") String authHeaderType,
                        @Value("${app.ai.actor-authorization:}") String actorAuthorization,
                        @Value("${app.ai.reasoning-effort:}") String reasoningEffort,
                        @Value("${app.ai.disable-response-storage:true}") boolean disableResponseStorage) {
        this.webClient = webClient;
        this.aiModelConfigService = aiModelConfigService;
        String normalizedApiType = normalizeApiType(apiType, apiUrl);
        this.defaultConfig = new AiCallConfig(
                normalizeProvider(provider),
                apiUrl,
                apiKey,
                model,
                maxOutputTokens,
                normalizeImageDetail(imageDetail),
                normalizedApiType,
                normalizeFallbackApiType(fallbackApiType, normalizedApiType),
                appendApiPath,
                openAiAuthEnabled,
                normalizeAuthHeaderType(authHeaderType),
                trim(actorAuthorization),
                normalizeReasoningEffort(reasoningEffort),
                disableResponseStorage
        );
    }

    OpenAiClient(WebClient webClient,
                 @Value("${app.ai.api-url}") String apiUrl,
                 @Value("${app.ai.api-key}") String apiKey,
                 @Value("${app.ai.model:gpt-5.1}") String model,
                 @Value("${app.ai.max-output-tokens:16384}") int maxOutputTokens,
                 @Value("${app.ai.image-detail:high}") String imageDetail,
                 @Value("${app.ai.api-type:auto}") String apiType,
                 @Value("${app.ai.fallback-api-type:off}") String fallbackApiType,
                 @Value("${app.ai.append-api-path:true}") boolean appendApiPath,
                 @Value("${app.ai.openai-auth-enabled:true}") boolean openAiAuthEnabled,
                 @Value("${app.ai.auth-header-type:bearer}") String authHeaderType,
                 @Value("${app.ai.actor-authorization:}") String actorAuthorization,
                 @Value("${app.ai.reasoning-effort:}") String reasoningEffort,
                 @Value("${app.ai.disable-response-storage:true}") boolean disableResponseStorage) {
        this(webClient, null, "openai", apiUrl, apiKey, model, maxOutputTokens, imageDetail, apiType,
                fallbackApiType, appendApiPath, openAiAuthEnabled, authHeaderType, actorAuthorization, reasoningEffort,
                disableResponseStorage);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        AiCallConfig config = resolveConfig();
        if ("claude".equals(config.provider())) {
            return callClaude(claudeTextRequest(config, systemPrompt, userPrompt), config,
                    userPrompt != null ? userPrompt.length() : 0, false);
        }

        RequestVariant primary = textRequest(config, config.apiType(), systemPrompt, userPrompt);
        RequestVariant fallback = textRequest(config, config.fallbackApiType(), systemPrompt, userPrompt);
        return callOpenAiWithFallback(config, primary, fallback, userPrompt != null ? userPrompt.length() : 0, false);
    }

    @Override
    public String generateStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        AiCallConfig config = resolveConfig();
        if ("claude".equals(config.provider())) {
            String result = callClaude(claudeTextRequest(config, systemPrompt, userPrompt), config,
                    userPrompt != null ? userPrompt.length() : 0, false);
            emitOnce(onDelta, result);
            return result;
        }

        RequestVariant primary = textRequest(config, config.apiType(), systemPrompt, userPrompt);
        RequestVariant fallback = textRequest(config, config.fallbackApiType(), systemPrompt, userPrompt);
        return callOpenAiStreamWithFallback(config, primary, fallback,
                userPrompt != null ? userPrompt.length() : 0, false, onDelta);
    }

    @Override
    public String generateWithImage(String systemPrompt, String userPrompt,
                                    String imageMime, String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return generate(systemPrompt, userPrompt);
        }

        AiCallConfig config = resolveConfig();
        if ("claude".equals(config.provider())) {
            return callClaude(claudeImageRequest(config, systemPrompt, userPrompt, imageMime, imageBase64),
                    config, userPrompt != null ? userPrompt.length() : 0, true);
        }
        if (!supportsImageInput(config)) {
            log.warn("Provider {} does not support image input, falling back to text-only generation: model={}",
                    config.provider(), config.model());
            String textOnlyPrompt = (userPrompt == null ? "" : userPrompt)
                    + "\n\n注意：当前模型配置不支持读取参考图，已仅根据文字描述生成。";
            return generate(systemPrompt, textOnlyPrompt);
        }

        RequestVariant primary = imageRequest(config, config.apiType(), systemPrompt, userPrompt, imageMime, imageBase64);
        RequestVariant fallback = imageRequest(config, config.fallbackApiType(), systemPrompt, userPrompt, imageMime, imageBase64);
        return callOpenAiWithFallback(config, primary, fallback, userPrompt != null ? userPrompt.length() : 0, true);
    }

    @Override
    public String generateWithImageStream(String systemPrompt, String userPrompt,
                                          String imageMime, String imageBase64,
                                          Consumer<String> onDelta) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return generateStream(systemPrompt, userPrompt, onDelta);
        }

        AiCallConfig config = resolveConfig();
        if ("claude".equals(config.provider())) {
            String result = callClaude(claudeImageRequest(config, systemPrompt, userPrompt, imageMime, imageBase64),
                    config, userPrompt != null ? userPrompt.length() : 0, true);
            emitOnce(onDelta, result);
            return result;
        }
        if (!supportsImageInput(config)) {
            log.warn("Provider {} does not support image input, falling back to text-only stream generation: model={}",
                    config.provider(), config.model());
            String textOnlyPrompt = (userPrompt == null ? "" : userPrompt)
                    + "\n\n注意：当前模型配置不支持读取参考图，已仅根据文字描述生成。";
            return generateStream(systemPrompt, textOnlyPrompt, onDelta);
        }

        RequestVariant primary = imageRequest(config, config.apiType(), systemPrompt, userPrompt, imageMime, imageBase64);
        RequestVariant fallback = imageRequest(config, config.fallbackApiType(), systemPrompt, userPrompt, imageMime, imageBase64);
        return callOpenAiStreamWithFallback(config, primary, fallback,
                userPrompt != null ? userPrompt.length() : 0, true, onDelta);
    }

    private AiCallConfig resolveConfig() {
        Long userId = AiRequestContext.getUserId();
        if (userId == null || aiModelConfigService == null) {
            return defaultConfig;
        }
        return aiModelConfigService.findEnabledByUserId(userId)
                .map(this::toCallConfig)
                .orElse(defaultConfig);
    }

    private AiCallConfig toCallConfig(AiModelConfig config) {
        String provider = normalizeProvider(config.getProvider());
        String apiUrl = notBlank(config.getBaseUrl(), defaultConfig.apiUrl());
        String apiType = normalizeApiType(notBlank(config.getApiType(), defaultApiType(provider, apiUrl)), apiUrl);
        String fallbackApiType = normalizeFallbackApiType(defaultConfig.fallbackApiType(), apiType);
        return new AiCallConfig(
                provider,
                apiUrl,
                notBlank(config.getApiKey(), defaultConfig.apiKey()),
                notBlank(config.getModel(), defaultConfig.model()),
                config.getMaxOutputTokens() == null ? defaultConfig.maxOutputTokens() : config.getMaxOutputTokens(),
                normalizeImageDetail(notBlank(config.getImageDetail(), defaultConfig.imageDetail())),
                apiType,
                fallbackApiType,
                config.getAppendApiPath() == null ? defaultConfig.appendApiPath() : config.getAppendApiPath(),
                config.getOpenAiAuthEnabled() == null ? defaultConfig.openAiAuthEnabled() : config.getOpenAiAuthEnabled(),
                normalizeAuthHeaderType(notBlank(config.getAuthHeaderType(), defaultConfig.authHeaderType())),
                notBlank(config.getActorAuthorization(), ""),
                normalizeReasoningEffort(notBlank(config.getReasoningEffort(), "")),
                config.getDisableResponseStorage() == null
                        ? defaultConfig.disableResponseStorage()
                        : config.getDisableResponseStorage()
        );
    }

    private RequestVariant textRequest(AiCallConfig config, String requestApiType, String systemPrompt, String userPrompt) {
        if (requestApiType == null || requestApiType.isBlank()) return null;
        Map<String, Object> body = "chat-completions".equals(requestApiType)
                ? chatTextRequest(config, systemPrompt, userPrompt)
                : responsesTextRequest(config, systemPrompt, userPrompt);
        return new RequestVariant(requestApiType, resolveApiUrl(config, requestApiType), body);
    }

    private RequestVariant imageRequest(AiCallConfig config, String requestApiType, String systemPrompt, String userPrompt,
                                        String imageMime, String imageBase64) {
        if (requestApiType == null || requestApiType.isBlank()) return null;
        String mediaType = normalizeMime(imageMime);
        Map<String, Object> body;
        if ("chat-completions".equals(requestApiType)) {
            body = chatImageRequest(config, systemPrompt, userPrompt, mediaType, imageBase64);
        } else {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "input_text", "text", userPrompt == null ? "" : userPrompt));

            Map<String, Object> imageBlock = new LinkedHashMap<>();
            imageBlock.put("type", "input_image");
            imageBlock.put("image_url", toDataUrl(mediaType, imageBase64));
            imageBlock.put("detail", config.imageDetail());
            content.add(imageBlock);

            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", content);

            body = responsesBaseRequest(config, systemPrompt);
            body.put("input", List.of(userMessage));
        }
        return new RequestVariant(requestApiType, resolveApiUrl(config, requestApiType), body);
    }

    private Map<String, Object> responsesTextRequest(AiCallConfig config, String systemPrompt, String userPrompt) {
        Map<String, Object> body = responsesBaseRequest(config, systemPrompt);
        body.put("input", userPrompt == null ? "" : userPrompt);
        return body;
    }

    private Map<String, Object> responsesBaseRequest(AiCallConfig config, String systemPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("instructions", systemPrompt == null ? "" : systemPrompt);
        body.put("max_output_tokens", config.maxOutputTokens());
        body.put("store", !config.disableResponseStorage());
        if (!config.reasoningEffort().isBlank()) {
            body.put("reasoning", Map.of("effort", config.reasoningEffort()));
        }
        return body;
    }

    private Map<String, Object> chatTextRequest(AiCallConfig config, String systemPrompt, String userPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        body.put("max_tokens", config.maxOutputTokens());
        body.put("temperature", 0.7);
        return body;
    }

    private Map<String, Object> chatImageRequest(AiCallConfig config, String systemPrompt, String userPrompt,
                                                 String imageMime, String imageBase64) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userPrompt == null ? "" : userPrompt));
        content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of(
                        "url", toDataUrl(imageMime, imageBase64),
                        "detail", config.imageDetail()
                )
        ));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt));
        messages.add(Map.of("role", "user", "content", content));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        body.put("max_tokens", config.maxOutputTokens());
        body.put("temperature", 0.7);
        return body;
    }

    private Map<String, Object> claudeTextRequest(AiCallConfig config, String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("max_tokens", config.maxOutputTokens());
        body.put("system", systemPrompt == null ? "" : systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)));
        return body;
    }

    private Map<String, Object> claudeImageRequest(AiCallConfig config, String systemPrompt, String userPrompt,
                                                   String imageMime, String imageBase64) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
                "type", "image",
                "source", Map.of(
                        "type", "base64",
                        "media_type", normalizeMime(imageMime),
                        "data", imageBase64
                )
        ));
        content.add(Map.of("type", "text", "text", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("max_tokens", config.maxOutputTokens());
        body.put("system", systemPrompt == null ? "" : systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return body;
    }

    private String callOpenAiWithFallback(AiCallConfig config, RequestVariant primary,
                                          RequestVariant fallback, int promptLen, boolean withImage) {
        try {
            return callOpenAi(config, primary, promptLen, withImage);
        } catch (RuntimeException e) {
            if (!shouldFallback(primary, fallback, e)) throw e;
            log.warn("OpenAI-compatible API failed, retrying fallback type={}, url={}, cause={}",
                    fallback.apiType(), fallback.apiUrl(), rootMessage(e));
            return callOpenAi(config, fallback, promptLen, withImage);
        }
    }

    private String callOpenAiStreamWithFallback(AiCallConfig config, RequestVariant primary,
                                                RequestVariant fallback, int promptLen, boolean withImage,
                                                Consumer<String> onDelta) {
        try {
            return callOpenAiStream(config, primary, promptLen, withImage, onDelta);
        } catch (RuntimeException e) {
            if (!shouldFallback(primary, fallback, e)) throw e;
            log.warn("OpenAI-compatible stream API failed, retrying fallback type={}, url={}, cause={}",
                    fallback.apiType(), fallback.apiUrl(), rootMessage(e));
            return callOpenAiStream(config, fallback, promptLen, withImage, onDelta);
        }
    }

    private String callOpenAi(AiCallConfig config, RequestVariant request, int promptLen, boolean withImage) {
        if (request == null) throw new RuntimeException("AI API type is empty");
        log.info("OpenAI-compatible API call: provider={}, type={}, model={}, url={}, auth={}, actorHeader={}, promptLen={}, withImage={}",
                config.provider(), request.apiType(), config.model(), request.apiUrl(),
                authDescription(config), !config.actorAuthorization().isBlank(), promptLen, withImage);
        if (config.openAiAuthEnabled() && config.apiKey().isBlank()) {
            throw new RuntimeException("OpenAI API Key 未配置，请在模型设置页填写 API Key");
        }
        try {
            String response = webClient.post()
                    .uri(request.apiUrl())
                    .headers(headers -> {
                        if (config.openAiAuthEnabled()) {
                            setApiKeyHeader(headers, config);
                        }
                        if (!config.actorAuthorization().isBlank()) {
                            headers.set("x-openai-actor-authorization", config.actorAuthorization());
                        }
                    })
                    .bodyValue(request.body())
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .filter(this::isRetryable)
                            .maxBackoff(Duration.ofSeconds(10))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .block();

            String text = extractContent(response);
            log.info("OpenAI-compatible API success: provider={}, model={}, textLen={}",
                    config.provider(), config.model(), text.length());
            return text;
        } catch (WebClientResponseException e) {
            String message = extractErrorMessage(e.getResponseBodyAsString());
            if (message.isBlank()) message = e.getStatusCode() + " " + e.getStatusText();
            throw new RuntimeException(String.format(
                    "OpenAI API 调用失败: %s (status=%s, provider=%s, type=%s, url=%s)",
                    message,
                    e.getStatusCode().value(),
                    config.provider(),
                    request.apiType(),
                    request.apiUrl()
            ), e);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI API 调用失败: " + e.getMessage(), e);
        }
    }

    private String callOpenAiStream(AiCallConfig config, RequestVariant request, int promptLen, boolean withImage,
                                    Consumer<String> onDelta) {
        if (request == null) throw new RuntimeException("AI API type is empty");
        Map<String, Object> body = new LinkedHashMap<>(request.body());
        body.put("stream", true);
        log.info("OpenAI-compatible stream API call: provider={}, type={}, model={}, url={}, auth={}, actorHeader={}, promptLen={}, withImage={}",
                config.provider(), request.apiType(), config.model(), request.apiUrl(),
                authDescription(config), !config.actorAuthorization().isBlank(), promptLen, withImage);
        if (config.openAiAuthEnabled() && config.apiKey().isBlank()) {
            throw new RuntimeException("OpenAI API Key 未配置，请在模型设置页填写 API Key");
        }

        StringBuilder raw = new StringBuilder();
        StringBuilder eventBuffer = new StringBuilder();
        StringBuilder text = new StringBuilder();
        try {
            webClient.post()
                    .uri(request.apiUrl())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(headers -> {
                        if (config.openAiAuthEnabled()) {
                            setApiKeyHeader(headers, config);
                        }
                        if (!config.actorAuthorization().isBlank()) {
                            headers.set("x-openai-actor-authorization", config.actorAuthorization());
                        }
                    })
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(chunk -> {
                        raw.append(chunk);
                        handleStreamChunk(chunk, eventBuffer, text, onDelta);
                    })
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .filter(this::isRetryable)
                            .maxBackoff(Duration.ofSeconds(10))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .blockLast();

            flushStreamBuffer(eventBuffer, text, onDelta);
            if (!text.isEmpty()) {
                String result = text.toString().trim();
                log.info("OpenAI-compatible stream API success: provider={}, model={}, textLen={}",
                        config.provider(), config.model(), result.length());
                return result;
            }

            String fallbackText = extractContent(raw.toString());
            emitOnce(onDelta, fallbackText);
            log.info("OpenAI-compatible stream API returned non-SSE JSON, parsed as full response: provider={}, model={}, textLen={}",
                    config.provider(), config.model(), fallbackText.length());
            return fallbackText;
        } catch (WebClientResponseException e) {
            String message = extractErrorMessage(e.getResponseBodyAsString());
            if (message.isBlank()) message = e.getStatusCode() + " " + e.getStatusText();
            throw new RuntimeException(String.format(
                    "OpenAI API 流式调用失败: %s (status=%s, provider=%s, type=%s, url=%s)",
                    message,
                    e.getStatusCode().value(),
                    config.provider(),
                    request.apiType(),
                    request.apiUrl()
            ), e);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI API 流式调用失败: " + e.getMessage(), e);
        }
    }

    private String callClaude(Map<String, Object> body, AiCallConfig config, int promptLen, boolean withImage) {
        String url = normalizeClaudeApiUrl(config.apiUrl(), config.appendApiPath());
        log.info("Claude API call: model={}, url={}, promptLen={}, withImage={}",
                config.model(), url, promptLen, withImage);
        if (config.apiKey().isBlank()) {
            throw new RuntimeException("Claude API Key 未配置，请在模型设置页填写 API Key");
        }
        try {
            String response = webClient.post()
                    .uri(url)
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .filter(this::isRetryable)
                            .maxBackoff(Duration.ofSeconds(10))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .block();
            String text = extractClaudeContent(response);
            log.info("Claude API success: model={}, textLen={}", config.model(), text.length());
            return text;
        } catch (WebClientResponseException e) {
            String message = extractErrorMessage(e.getResponseBodyAsString());
            if (message.isBlank()) message = e.getStatusCode() + " " + e.getStatusText();
            throw new RuntimeException("Claude API 调用失败: " + message, e);
        } catch (Exception e) {
            throw new RuntimeException("Claude API 调用失败: " + e.getMessage(), e);
        }
    }

    private String resolveApiUrl(AiCallConfig config, String requestApiType) {
        return normalizeApiUrl(config.apiUrl(), requestApiType, config.appendApiPath());
    }

    private boolean shouldFallback(RequestVariant primary, RequestVariant fallback, Throwable throwable) {
        if (primary == null || fallback == null || fallback.apiType().isBlank()) return false;
        if (!"responses".equals(primary.apiType()) || primary.apiType().equals(fallback.apiType())) return false;
        WebClientResponseException responseException = findCause(throwable, WebClientResponseException.class);
        if (responseException == null) return hasTransportFailureMarker(throwable);
        int code = responseException.getStatusCode().value();
        return code != 401 && code != 403 && code != 429;
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException e) {
            int code = e.getStatusCode().value();
            return code == 429 || e.getStatusCode().is5xxServerError() || e.getStatusCode().is2xxSuccessful();
        }
        return true;
    }

    private void handleStreamChunk(String chunk, StringBuilder eventBuffer, StringBuilder text,
                                   Consumer<String> onDelta) {
        if (chunk == null || chunk.isEmpty()) return;
        String normalized = chunk.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = normalized.trim();
        if (trimmed.startsWith("{") || "[DONE]".equals(trimmed)) {
            processStreamEvent(trimmed, text, onDelta);
            return;
        }

        eventBuffer.append(normalized);
        int boundary;
        while ((boundary = eventBuffer.indexOf("\n\n")) >= 0) {
            String eventBlock = eventBuffer.substring(0, boundary);
            eventBuffer.delete(0, boundary + 2);
            processStreamEvent(eventBlock, text, onDelta);
        }
    }

    private void flushStreamBuffer(StringBuilder eventBuffer, StringBuilder text, Consumer<String> onDelta) {
        if (eventBuffer.isEmpty()) return;
        String remaining = eventBuffer.toString().trim();
        eventBuffer.setLength(0);
        if (!remaining.isBlank()) processStreamEvent(remaining, text, onDelta);
    }

    private void processStreamEvent(String eventBlock, StringBuilder text, Consumer<String> onDelta) {
        if (eventBlock == null || eventBlock.isBlank()) return;
        String eventName = "";
        StringBuilder data = new StringBuilder();
        for (String line : eventBlock.split("\n", -1)) {
            if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).trim());
            }
        }
        String payload = data.isEmpty() ? eventBlock.trim() : data.toString().trim();
        if (payload.isBlank() || "[DONE]".equals(payload)) return;

        try {
            JsonNode root = objectMapper.readTree(payload);
            String errorMessage = root.path("error").path("message").asText("");
            if (!errorMessage.isBlank()) throw new RuntimeException(errorMessage);

            String type = root.path("type").asText(eventName);
            if ("response.failed".equals(type) || "response.error".equals(type)) {
                String message = root.path("message").asText("");
                if (message.isBlank()) message = root.path("response").path("error").path("message").asText("");
                throw new RuntimeException(message.isBlank() ? "AI stream failed" : message);
            }

            String delta = "";
            if ("response.output_text.delta".equals(type)) {
                delta = root.path("delta").asText("");
            } else {
                delta = extractChatStreamDelta(root);
            }
            if (!delta.isBlank()) {
                text.append(delta);
                if (onDelta != null) onDelta.accept(delta);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Ignore unparsable stream event: event={}, cause={}", eventName, e.getMessage());
        }
    }

    private String extractChatStreamDelta(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode choice : choices) {
            String content = extractMessageText(choice.path("delta").path("content"));
            if (content.isBlank()) content = extractMessageText(choice.path("message").path("content"));
            if (!content.isBlank()) sb.append(content);
        }
        return sb.toString();
    }

    String extractContent(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new RuntimeException("OpenAI API 返回为空");
        }
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String errorMessage = root.path("error").path("message").asText("");
            if (!errorMessage.isBlank()) throw new RuntimeException(errorMessage);

            String outputText = root.path("output_text").asText("");
            if (!outputText.isBlank()) return outputText;

            StringBuilder sb = new StringBuilder();
            JsonNode output = root.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) appendTextContent(sb, item.path("content"));
            }
            if (!sb.isEmpty()) return sb.toString().trim();

            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = extractMessageText(message.path("content"));
                if (!content.isBlank()) return content;
                // 部分中转/Grok 兼容层会把正文放在其它字段
                content = extractMessageText(message.path("reasoning_content"));
                if (!content.isBlank()) return content;
                content = extractMessageText(message.path("text"));
                if (!content.isBlank()) return content;
            }
            if (root.isObject()) {
                throw new RuntimeException("OpenAI API 返回格式不是模型生成结果，请检查 api-url/api-type/append-api-path 配置");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OpenAI response parse failed, returning raw text: {}", e.getMessage());
            return responseJson;
        }
        return responseJson;
    }

    String extractClaudeContent(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new RuntimeException("Claude API 返回为空");
        }
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String errorMessage = root.path("error").path("message").asText("");
            if (!errorMessage.isBlank()) throw new RuntimeException(errorMessage);

            StringBuilder sb = new StringBuilder();
            JsonNode content = root.path("content");
            if (content.isArray()) appendTextContent(sb, content);
            if (!sb.isEmpty()) return sb.toString().trim();
            if (root.isObject()) throw new RuntimeException("Claude API 返回格式不是模型生成结果");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Claude response parse failed, returning raw text: {}", e.getMessage());
            return responseJson;
        }
        return responseJson;
    }

    String extractErrorMessage(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) return message;
            return root.path("message").asText("");
        } catch (Exception e) {
            return responseJson;
        }
    }

    private void appendTextContent(StringBuilder sb, JsonNode content) {
        if (!content.isArray()) return;
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("output_text".equals(type) || "text".equals(type)) {
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(text);
                }
            }
        }
    }

    /**
     * Chat Completions 的 message.content 可能是 string，也可能是多段数组（中转站/Grok 兼容层）。
     */
    private String extractMessageText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                if (block.isTextual()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(block.asText());
                    continue;
                }
                String type = block.path("type").asText("");
                String text = block.path("text").asText("");
                if (text.isBlank()) text = block.path("content").asText("");
                if (!text.isBlank() && (type.isBlank() || "text".equals(type) || "output_text".equals(type))) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(text);
                }
            }
            return sb.toString().trim();
        }
        if (contentNode.isObject()) {
            String text = contentNode.path("text").asText("");
            if (!text.isBlank()) return text;
            return contentNode.path("content").asText("");
        }
        return "";
    }

    static String normalizeApiUrl(String apiUrl, String apiType, boolean appendApiPath) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return "chat-completions".equals(apiType)
                    ? "https://api.openai.com/v1/chat/completions"
                    : "https://api.openai.com/v1/responses";
        }
        String trimmed = apiUrl.trim();
        if (!appendApiPath || !isRootUrl(trimmed)) return trimmed;
        String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return "chat-completions".equals(apiType)
                ? base + "/v1/chat/completions"
                : base + "/v1/responses";
    }

    static String normalizeClaudeApiUrl(String apiUrl, boolean appendApiPath) {
        if (apiUrl == null || apiUrl.isBlank()) return "https://api.anthropic.com/v1/messages";
        String trimmed = apiUrl.trim();
        if (!appendApiPath || !isRootUrl(trimmed)) return trimmed;
        String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return base + "/v1/messages";
    }

    private static String normalizeProvider(String provider) {
        String p = trim(provider).toLowerCase();
        if (p.isBlank()) return "openai";
        if ("anthropic".equals(p)) return "claude";
        if ("openai-compatible".equals(p) || "custom".equals(p)) return "custom";
        return p;
    }

    private static void setApiKeyHeader(org.springframework.http.HttpHeaders headers, AiCallConfig config) {
        switch (config.authHeaderType()) {
            case "x-api-key" -> headers.set("x-api-key", config.apiKey());
            case "x-goog-api-key" -> headers.set("x-goog-api-key", config.apiKey());
            case "none" -> {
            }
            default -> headers.setBearerAuth(config.apiKey());
        }
    }

    private static String normalizeApiType(String apiType, String apiUrl) {
        String t = apiType == null ? "auto" : apiType.toLowerCase().trim();
        if ("chat".equals(t) || "chat-completions".equals(t) || "chat_completions".equals(t)) {
            return "chat-completions";
        }
        if ("responses".equals(t) || "response".equals(t)) return "responses";
        String url = apiUrl == null ? "" : apiUrl.toLowerCase();
        if (url.contains("/chat/completions")) return "chat-completions";
        if (url.contains("/responses")) return "responses";
        return isLikelyRelayRootUrl(apiUrl) ? "chat-completions" : "responses";
    }

    private static String normalizeFallbackApiType(String fallbackApiType, String primaryApiType) {
        if (fallbackApiType == null || fallbackApiType.isBlank()) return "";
        String t = fallbackApiType.toLowerCase().trim();
        if ("none".equals(t) || "off".equals(t) || "disabled".equals(t) || "false".equals(t)) return "";
        String normalized = normalizeApiType(t, "");
        return normalized.equals(primaryApiType) ? "" : normalized;
    }

    private static String defaultApiType(String provider, String apiUrl) {
        if ("deepseek".equals(provider)) return "chat-completions";
        return normalizeApiType("auto", apiUrl);
    }

    private static boolean supportsImageInput(AiCallConfig config) {
        return switch (config.provider()) {
            case "deepseek" -> false;
            default -> true;
        };
    }

    private static String normalizeReasoningEffort(String effort) {
        return trim(effort).toLowerCase();
    }

    private static String normalizeAuthHeaderType(String value) {
        String type = trim(value).toLowerCase();
        return switch (type) {
            case "x-api-key", "x-goog-api-key", "none" -> type;
            default -> "bearer";
        };
    }

    private static String authDescription(AiCallConfig config) {
        if (!config.openAiAuthEnabled()) return "disabled";
        return config.authHeaderType();
    }

    private static void emitOnce(Consumer<String> onDelta, String result) {
        if (onDelta != null && result != null && !result.isBlank()) {
            onDelta.accept(result);
        }
    }

    private static String normalizeImageDetail(String detail) {
        String d = trim(detail).toLowerCase();
        return switch (d) {
            case "low", "high", "auto" -> d;
            default -> "high";
        };
    }

    private static String normalizeMime(String mime) {
        String m = trim(mime).toLowerCase();
        if (m.isBlank()) return "image/png";
        return switch (m) {
            case "image/jpg", "image/jpeg" -> "image/jpeg";
            case "image/png" -> "image/png";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> m.startsWith("image/") ? m : "image/png";
        };
    }

    private static String toDataUrl(String mime, String imageBase64) {
        return "data:" + mime + ";base64," + imageBase64;
    }

    private static boolean isLikelyRelayRootUrl(String apiUrl) {
        if (!isRootUrl(apiUrl)) return false;
        try {
            String host = URI.create(apiUrl.trim()).getHost();
            return host != null && !host.equalsIgnoreCase("api.openai.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isRootUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return false;
        try {
            String path = URI.create(apiUrl.trim()).getPath();
            return path == null || path.isBlank() || "/".equals(path);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (type.isInstance(cursor)) return type.cast(cursor);
            cursor = cursor.getCause();
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        Throwable root = throwable;
        while (cursor != null) {
            root = cursor;
            cursor = cursor.getCause();
        }
        return root == null ? "" : root.getMessage();
    }

    private static boolean hasTransportFailureMarker(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String className = cursor.getClass().getName().toLowerCase();
            String message = cursor.getMessage() == null ? "" : cursor.getMessage().toLowerCase();
            if (className.contains("prematureclose")
                    || className.contains("closedchannel")
                    || className.contains("ssl")
                    || className.contains("timeout")
                    || message.contains("prematurely closed")
                    || message.contains("connection reset")
                    || message.contains("closed before response")
                    || message.contains("timed out")
                    || message.contains("ssl")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String notBlank(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private record AiCallConfig(
            String provider,
            String apiUrl,
            String apiKey,
            String model,
            int maxOutputTokens,
            String imageDetail,
            String apiType,
            String fallbackApiType,
            boolean appendApiPath,
            boolean openAiAuthEnabled,
            String authHeaderType,
            String actorAuthorization,
            String reasoningEffort,
            boolean disableResponseStorage
    ) {
    }

    private record RequestVariant(String apiType, String apiUrl, Map<String, Object> body) {
    }
}
