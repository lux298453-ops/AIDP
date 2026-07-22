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
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API 客户端 —— OpenAI 兼容格式。
 *
 * API 文档: https://platform.deepseek.com/api-docs
 * Endpoint: POST https://api.deepseek.com/chat/completions
 *
 * 激活方式: spring.profiles.active=deepseek
 */
@Slf4j
@Component
@Profile("deepseek")
public class DeepSeekClient implements AiClient {

    private final WebClient webClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekClient(WebClient webClient,
                          @Value("${app.ai.api-url}") String apiUrl,
                          @Value("${app.ai.api-key}") String apiKey,
                          @Value("${app.ai.model:deepseek-chat}") String model) {
        this.webClient = webClient;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", 4096,
                "temperature", 0.7
        );

        log.info("DeepSeek API 调用: model={}, promptLen={}", model, userPrompt.length());

        try {
            String response = webClient.post()
                    .uri(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .maxBackoff(Duration.ofSeconds(10)))
                    .block();

            // 提取 choices[0].message.content（OpenAI 兼容格式）
            String text = extractContent(response);
            log.info("DeepSeek API 成功: model={}, textLen={}", model, text.length());
            return text;
        } catch (Exception e) {
            log.error("DeepSeek API 失败: {}", e.getMessage());
            throw new RuntimeException("DeepSeek API 调用失败: " + e.getMessage(), e);
        }
    }

    /** 从 OpenAI 响应中提取 text 内容 */
    private String extractContent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.warn("DeepSeek 响应解析失败，返回原始文本: {}", e.getMessage());
            return responseJson;
        }
    }
}
