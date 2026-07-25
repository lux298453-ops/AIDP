package com.example.aidocumentplatform.ai.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude API 客户端 —— 通过 WebClient 调用 Anthropic Messages API。
 *
 * API 文档: https://docs.anthropic.com/en/api/messages
 * 当 profile 不含 "deepseek" 时激活（默认）。
 * 支持 generateWithImage 多模态（风格参考图）。
 */
@Slf4j
@Component
@Profile("!deepseek & !openai")
public class ClaudeClient implements AiClient {

    private final WebClient webClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeClient(WebClient webClient,
                        @Value("${app.ai.api-url}") String apiUrl,
                        @Value("${app.ai.api-key}") String apiKey,
                        @Value("${app.ai.model:claude-sonnet-4-20250514}") String model) {
        this.webClient = webClient;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 16384,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );
        return call(body, userPrompt.length(), false);
    }

    @Override
    public String generateWithImage(String systemPrompt, String userPrompt,
                                    String imageMime, String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return generate(systemPrompt, userPrompt);
        }
        String mediaType = normalizeMime(imageMime);

        // Anthropic Messages：content 为多模态数组
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image");
        imageBlock.put("source", Map.of(
                "type", "base64",
                "media_type", mediaType,
                "data", imageBase64
        ));
        content.add(imageBlock);
        content.add(Map.of("type", "text", "text", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 16384);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));

        log.info("Claude 多模态调用: model={}, imageMime={}, b64Len={}", model, mediaType, imageBase64.length());
        return call(body, userPrompt.length(), true);
    }

    private String call(Map<String, Object> body, int promptLen, boolean withImage) {
        log.info("Claude API 调用: model={}, promptLen={}, withImage={}", model, promptLen, withImage);
        try {
            String response = webClient.post()
                    .uri(apiUrl)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .maxBackoff(Duration.ofSeconds(10)))
                    .block();

            String text = extractContent(response);
            log.info("Claude API 成功: model={}, textLen={}", model, text.length());
            return text;
        } catch (Exception e) {
            log.error("Claude API 失败: {}", e.getMessage());
            throw new RuntimeException("Claude API 调用失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeMime(String mime) {
        if (mime == null || mime.isBlank()) return "image/png";
        String m = mime.toLowerCase().trim();
        return switch (m) {
            case "image/jpg", "image/jpeg" -> "image/jpeg";
            case "image/png" -> "image/png";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> m.startsWith("image/") ? m : "image/png";
        };
    }

    private String extractContent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            return root.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            log.warn("Claude 响应解析失败，返回原始文本: {}", e.getMessage());
            return responseJson;
        }
    }
}
