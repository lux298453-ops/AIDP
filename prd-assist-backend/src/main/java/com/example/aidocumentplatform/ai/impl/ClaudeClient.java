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
import java.util.List;
import java.util.Map;

/**
 * Claude API 客户端 —— 通过 WebClient 调用 Anthropic Messages API。
 *
 * API 文档: https://docs.anthropic.com/en/api/messages
 * 使用的 endpoint: POST https://api.anthropic.com/v1/messages
 *
 * 当 profile 不含 "deepseek" 时激活（默认）。
 */
@Slf4j
@Component
@Profile("!deepseek")
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

        log.info("Claude API 调用: model={}, promptLen={}", model, userPrompt.length());

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

            // 提取 content[0].text（Anthropic 格式）
            String text = extractContent(response);
            log.info("Claude API 成功: model={}, textLen={}", model, text.length());
            return text;
        } catch (Exception e) {
            log.error("Claude API 失败: {}", e.getMessage());
            throw new RuntimeException("Claude API 调用失败: " + e.getMessage(), e);
        }
    }

    /** 从 Anthropic 响应中提取 text 内容 */
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
