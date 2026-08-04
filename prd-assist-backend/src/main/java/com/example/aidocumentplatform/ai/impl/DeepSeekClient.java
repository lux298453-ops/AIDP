package com.example.aidocumentplatform.ai.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                "max_tokens", 16384,
                "temperature", 0.7
        );

        log.info("DeepSeek API call: model={}, promptLen={}", model, userPrompt.length());

        try {
            String response = webClient.post()
                    .uri(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(retrySpec())
                    .block();

            String text = extractContent(response);
            log.info("DeepSeek API success: model={}, textLen={}", model, text.length());
            return text;
        } catch (Exception e) {
            log.error("DeepSeek API failed: {}", e.getMessage());
            throw new RuntimeException("DeepSeek API 调用失败: " + e.getMessage(), e);
        }
    }

    private Retry retrySpec() {
        return Retry.backoff(2, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(DeepSeekClient::isRetryableException)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof WebClientResponseException responseException) {
            return responseException.getStatusCode().is5xxServerError()
                    || responseException.getStatusCode().value() == 408
                    || responseException.getStatusCode().value() == 429;
        }
        return hasTransportFailureMarker(throwable);
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

    private String extractContent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.warn("DeepSeek response parse failed, returning raw text: {}", e.getMessage());
            return responseJson;
        }
    }
}