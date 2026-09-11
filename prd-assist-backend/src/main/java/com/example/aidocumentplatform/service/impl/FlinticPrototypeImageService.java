package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;
import com.example.aidocumentplatform.model.entity.AiModelConfig;
import com.example.aidocumentplatform.service.AiModelConfigService;
import com.example.aidocumentplatform.service.PrototypeImageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlinticPrototypeImageService implements PrototypeImageService {

    private final WebClient webClient;
    private final AiModelConfigService aiModelConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.ai.image.enabled:true}")
    private boolean enabled;

    @Value("${app.ai.image.base-url:}")
    private String configuredBaseUrl;

    @Value("${app.ai.image.api-key:}")
    private String configuredApiKey;

    @Value("${app.ai.image.model:gpt-image-2}")
    private String model;

    @Value("${app.ai.image.quality:medium}")
    private String quality;

    @Value("${app.ai.image.timeout-seconds:180}")
    private long timeoutSeconds;

    @Override
    public GeneratedImage generate(PrototypeAssetPlan plan, Long userId) {
        if (!enabled) throw new IllegalStateException("原型图片生成未启用");
        if (plan == null || !plan.isRequired() || plan.getPrompt() == null || plan.getPrompt().isBlank()) {
            throw new IllegalArgumentException("缺少可执行的原型素材计划");
        }

        AiModelConfig config = aiModelConfigService.findEnabledByUserId(userId).orElse(null);
        String baseUrl = firstNonBlank(configuredBaseUrl, config == null ? null : config.getBaseUrl());
        String apiKey = firstNonBlank(configuredApiKey, config == null ? null : config.getApiKey());
        if (baseUrl.isBlank()) throw new IllegalStateException("未配置图片生成 API Base URL");
        if (apiKey.isBlank()) throw new IllegalStateException("未配置图片生成 API Key");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", buildImagePrompt(plan));
        body.put("size", resolveSize(plan.getAspectRatio()));
        body.put("quality", quality);
        body.put("output_format", "png");
        // Some relay image backends paint a checkerboard when asked for transparency.
        // A chroma background can be validated and removed deterministically after generation.
        body.put("background", "opaque");

        String url = imagesEndpoint(baseUrl);
        String rawResponse = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyHeaders(headers, config, apiKey))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(timeoutSeconds));

        JsonNode response;
        try {
            response = rawResponse == null || rawResponse.isBlank()
                    ? null
                    : objectMapper.readTree(rawResponse);
        } catch (Exception e) {
            throw new IllegalStateException("图片生成接口返回了无法解析的 JSON", e);
        }

        JsonNode item = response == null ? null : response.path("data").path(0);
        String base64 = item == null ? "" : item.path("b64_json").asText("");
        if (base64.isBlank()) {
            throw new IllegalStateException("图片生成接口未返回 data[0].b64_json");
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (plan.isTransparentBackground()) {
            bytes = removeChromaBackgroundOrKeepOriginal(bytes);
        }
        log.info("原型核心素材生成成功: model={}, role={}, bytes={}", model, plan.getRole(), bytes.length);
        return new GeneratedImage(bytes, "image/png", ".png");
    }

    private String buildImagePrompt(PrototypeAssetPlan plan) {
        String background = plan.isTransparentBackground()
                ? "Isolate the complete subject on a perfectly flat, uniform pure chroma green (#00FF00) background. "
                    + "The green background must contain no gradient, texture, shadow, glow, checkerboard, transparency grid, scenery, or floor. "
                    + "Keep clean separated edges and do not use green on the subject."
                : "Create a complete background suitable for direct use as a UI hero visual.";
        return plan.getPrompt().trim() + "\n" + background
                + "\nRender exactly one single continuous scene filling the whole canvas. "
                + "Never split the image into multiple panels, frames, grids, columns, or side-by-side previews."
                + "\nDo not render UI controls, buttons, navigation, badges, logos, watermarks, or readable text.";
    }

    private String resolveSize(String ratio) {
        return switch (ratio == null ? "" : ratio) {
            case "9:16", "3:4" -> "1024x1536";
            case "16:9", "4:3" -> "1536x1024";
            default -> "1024x1024";
        };
    }

    public static byte[] removeChromaBackground(byte[] input) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(input));
            if (source == null) throw new IllegalStateException("图片生成接口返回的不是有效图片");

            BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int keyedPixels = 0;
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int argb = source.getRGB(x, y);
                    int r = (argb >>> 16) & 0xff;
                    int g = (argb >>> 8) & 0xff;
                    int b = argb & 0xff;
                    int greenDominance = g - Math.max(r, b);
                    int alpha = greenDominance >= 70 && g >= 135
                            ? 0
                            : greenDominance > 20 && g >= 100
                            ? Math.min(255, Math.max(0, (70 - greenDominance) * 255 / 50))
                            : 255;
                    if (alpha < 255) keyedPixels++;

                    // Reduce green spill on soft anti-aliased edges.
                    if (alpha > 0 && greenDominance > 0) {
                        g = Math.min(g, Math.max(r, b));
                    }
                    output.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
                }
            }

            double keyedRatio = keyedPixels / (double) (source.getWidth() * source.getHeight());
            if (keyedRatio < 0.02) {
                throw new IllegalStateException("图片模型未按要求返回纯色可抠除背景，已拒绝嵌入假透明素材");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(output, "png", buffer);
            log.info("原型素材色键抠图完成: size={}x{}, transparentRatio={}%",
                    source.getWidth(), source.getHeight(), Math.round(keyedRatio * 100));
            return buffer.toByteArray();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("原型素材透明背景处理失败", e);
        }
    }

    public static byte[] removeChromaBackgroundOrKeepOriginal(byte[] input) {
        try {
            return removeChromaBackground(input);
        } catch (IllegalStateException e) {
            log.warn("原型素材未形成可可靠抠除的色键背景，保留原始不透明图片: {}", e.getMessage());
            return input;
        }
    }

    private void applyHeaders(HttpHeaders headers, AiModelConfig config, String apiKey) {
        String type = config == null ? "bearer" : firstNonBlank(config.getAuthHeaderType(), "bearer").toLowerCase();
        // Image API requires a real key even when the text Responses endpoint uses actor authorization mode.
        switch (type) {
            case "x-api-key" -> headers.set("x-api-key", apiKey);
            case "x-goog-api-key" -> headers.set("x-goog-api-key", apiKey);
            default -> headers.setBearerAuth(apiKey);
        }
        if (config != null && config.getActorAuthorization() != null && !config.getActorAuthorization().isBlank()) {
            headers.set("x-openai-actor-authorization", config.getActorAuthorization().trim());
        }
    }

    public static String imagesEndpoint(String baseUrl) {
        String value = baseUrl.trim();
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath();
            for (String suffix : new String[]{"/v1/responses", "/v1/chat/completions", "/v1/messages", "/v1/images/generations"}) {
                if (path.endsWith(suffix)) {
                    value = value.substring(0, value.length() - suffix.length());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        value = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return value + "/v1/images/generations";
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        return second == null ? "" : second.trim();
    }
}
