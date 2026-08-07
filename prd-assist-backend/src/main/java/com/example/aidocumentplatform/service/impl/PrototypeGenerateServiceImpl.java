package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrototypeGenerateServiceImpl implements PrototypeGenerateService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final AiClient aiClient;
    private final PrototypePromptTemplate promptTemplate;
    private final TaskService taskService;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Long submit(PrototypeGenerateRequest request, Long userId) {
        boolean hasRef = request.getReferenceImageBase64() != null
                && !request.getReferenceImageBase64().isBlank();
        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PROTOTYPE, buildTaskInput(request, hasRef));
        AsyncTask task = reservation.task();
        log.info("原型生成任务: taskId={}, type={}, platform={}, hasRef={}",
                task.getId(), request.getPrototypeType(), request.getPlatform(), hasRef);
        if (reservation.created()) {
            applicationContext.getBean(PrototypeGenerateServiceImpl.class)
                    .execute(task.getId(), request, userId);
        }
        return task.getId();
    }

    private String buildTaskInput(PrototypeGenerateRequest request, boolean hasRef) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("platform", request.getPlatform().name());
            root.put("prototypeType", request.getPrototypeType().name());
            root.put("pageMorphology", request.getPageMorphology() == null ? "" : request.getPageMorphology().name());
            root.put("description", request.getDescription());
            root.put("hasReferenceImage", hasRef);
            if (hasRef && request.getReferenceImageFileName() != null) {
                root.put("referenceImageFileName", request.getReferenceImageFileName());
            }
            if (request.getReferenceImagePath() != null) {
                root.put("referenceImagePath", request.getReferenceImagePath());
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建原型任务参数失败", e);
        }
    }
    @Async("asyncTaskExecutor")
    public void execute(Long taskId, PrototypeGenerateRequest request, Long userId) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) return;
        try {
            asyncTaskLifecycleService.markRunning(taskId);
            boolean hasRef = request.getReferenceImageBase64() != null
                    && !request.getReferenceImageBase64().isBlank();
            taskService.pushProgress(taskId, 15,
                    hasRef ? "AI 正在根据参考图生成原型..." : "AI 正在生成原型...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    request.getDescription(),
                    request.getPlatform(),
                    request.getPrototypeType(),
                    hasRef,
                    request.getReferenceImageFileName(),
                    request.getPageMorphology());

            String aiOutput;
            AiRequestContext.setUserId(userId);
            try {
                if (hasRef) {
                    aiOutput = aiClient.generateWithImageStream(
                            systemPrompt,
                            userPrompt,
                            request.getReferenceImageMimeType(),
                            request.getReferenceImageBase64(),
                            delta -> taskService.pushContentDelta(taskId, delta));
                } else {
                    aiOutput = aiClient.generateStream(systemPrompt, userPrompt,
                            delta -> taskService.pushContentDelta(taskId, delta));
                }
            } finally {
                AiRequestContext.clear();
            }

            // 先清洗/落库再推 SSE，避免进度推送异常影响保存
            String content = request.getPrototypeType() == PrototypeType.MULTI_PAGE
                    ? cleanJson(aiOutput, request.getPlatform())
                    : cleanHtml(aiOutput, request.getPlatform());

            PrototypeResult proto = PrototypeResult.builder()
                    .userId(userId).taskId(taskId).prdDocumentId(request.getPrdDocumentId())
                    .prototypeType(request.getPrototypeType()).platform(request.getPlatform())
                    .content(content).build();
            proto = asyncTaskLifecycleService.savePrototypeResultAndMarkSuccess(taskId, proto);
            taskService.pushProgress(taskId, 100, "原型生成完成");
            log.info("原型生成成功: taskId={}, resultId={}, type={}, hasRef={}, len={}",
                    taskId, proto.getId(), request.getPrototypeType(), hasRef, content.length());

        } catch (Exception e) {
            log.error("原型生成失败: taskId={}", taskId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
            taskService.pushProgress(taskId, 0, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 清洗模型输出为可预览 HTML。
     * Grok 等模型常夹带 markdown / 思考段落 / 不完整文档，这里尽量抽出完整 HTML，
     * 并对「只有 class、几乎没有 CSS」的结果注入基础样式，避免预览完全裸奔。
     */
    private String cleanHtml(String raw, Platform platform) {
        if (raw == null || raw.isBlank()) return "";
        String s = stripCodeFence(raw.trim());

        // 去掉常见“思考/说明”前缀，定位到真正的 HTML
        int doctype = indexOfIgnoreCase(s, "<!DOCTYPE");
        int htmlTag = indexOfIgnoreCase(s, "<html");
        int start = -1;
        if (doctype >= 0 && (htmlTag < 0 || doctype <= htmlTag)) start = doctype;
        else if (htmlTag >= 0) start = htmlTag;
        else start = s.indexOf('<');
        if (start > 0) s = s.substring(start);

        int end = s.lastIndexOf('>');
        if (end > 0 && end < s.length() - 1) s = s.substring(0, end + 1);
        s = s.trim();
        s = repairMalformedCssValues(s);

        // 模型只吐了片段时，包一层最小文档结构
        if (!looksLikeFullDocument(s)) {
            s = wrapAsDocument(s);
        }

        s = addPlatformMarker(s, platform);
        // 唯一样式来源：平台扁平组件包（co-*）
        s = injectPrototypeQualityStyles(s, platform);
        return s;
    }

    /**
     * 多页 JSON：清洗数组，并对每个 page.html 再走 cleanHtml，
     * 避免多页场景下样式缺失 / 文档结构不完整。
     */
    private String cleanJson(String raw, Platform platform) {
        if (raw == null) return "[]";
        String s = stripCodeFence(raw.trim());
        int start = s.indexOf('['), end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return s;
        String json = s.substring(start, end + 1);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) return json;
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            int order = 1;
            for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                com.fasterxml.jackson.databind.node.ObjectNode page = mapper.createObjectNode();
                String title = item.path("title").asText("页面 " + order);
                int ord = item.path("order").asInt(order);
                String html = item.path("html").asText("");
                if (html.isBlank() && item.isTextual()) html = item.asText("");
                page.put("title", title);
                page.put("order", ord);
                page.put("html", cleanHtml(html, platform));
                out.add(page);
                order++;
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("多页 JSON 二次清洗失败，返回原始数组片段: {}", e.getMessage());
            return json;
        }
    }

    private static String stripCodeFence(String s) {
        if (s.startsWith("```html") || s.startsWith("```HTML")) {
            s = s.substring(s.indexOf('\n') >= 0 ? s.indexOf('\n') + 1 : 7);
        } else if (s.startsWith("```json") || s.startsWith("```JSON")) {
            s = s.substring(s.indexOf('\n') >= 0 ? s.indexOf('\n') + 1 : 7);
        } else if (s.startsWith("```")) {
            s = s.substring(s.indexOf('\n') >= 0 ? s.indexOf('\n') + 1 : 3);
        }
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        return s.trim();
    }

    private static boolean looksLikeFullDocument(String html) {
        String lower = html.toLowerCase();
        return lower.contains("<html") || lower.contains("<!doctype");
    }

    private static String wrapAsDocument(String fragment) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>原型预览</title>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(fragment);
    }

    private static String addPlatformMarker(String html, Platform platform) {
        if (html == null || html.isBlank() || platform == null) return html;
        String lower = html.toLowerCase();
        int bodyOpen = lower.indexOf("<body");
        if (bodyOpen < 0) return html;
        int bodyTagEnd = html.indexOf('>', bodyOpen);
        if (bodyTagEnd < 0) return html;

        String bodyTag = html.substring(bodyOpen, bodyTagEnd + 1);
        String platformName = platform.name();
        if (!bodyTag.toLowerCase().contains("data-proto-platform=")) {
            bodyTag = bodyTag.substring(0, bodyTag.length() - 1)
                    + " data-proto-platform=\"" + platformName + "\">";
        }
        if (bodyTag.toLowerCase().contains("class=")) {
            bodyTag = bodyTag.replaceFirst("class\\s*=\\s*\"([^\"]*)\"",
                    "class=\"$1 proto-platform-" + platformName.toLowerCase() + "\"");
        } else {
            bodyTag = bodyTag.substring(0, bodyTag.length() - 1)
                    + " class=\"proto-platform-" + platformName.toLowerCase() + "\">";
        }
        return html.substring(0, bodyOpen) + bodyTag + html.substring(bodyTagEnd + 1);
    }

    private static String repairMalformedCssValues(String html) {
        if (html == null || html.isBlank() || !html.toLowerCase().contains("<style")) return html;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?is)<style([^>]*)>(.*?)</style>");
        java.util.regex.Matcher matcher = pattern.matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String css = matcher.group(2);
            String fixed = repairCssBlock(css);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    "<style" + matcher.group(1) + ">" + fixed + "</style>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String repairCssBlock(String css) {
        if (css == null || css.isBlank()) return css;
        String fixed = css;
        // 部分模型会把 CSS 值里的必要空格吞掉，例如 padding:014px / 018px54px / #fff0%。
        fixed = fixed.replaceAll("(?i)([:\\s,(]0)(\\d+(?:px|rpx|rem|em|vh|vw|%))", "$1 $2");
        fixed = fixed.replaceAll("(?i)(\\d(?:px|rpx|rem|em|vh|vw|%))(\\d)", "$1 $2");
        fixed = fixed.replaceAll("(?i)(#[0-9a-f]{3}|#[0-9a-f]{6})(\\d+%)", "$1 $2");
        fixed = fixed.replaceAll("(?i)\\b(transparent|black|white|red|blue|green)(\\d+%)", "$1 $2");
        fixed = fixed.replaceAll("(?i)\\bat(\\d)", "at $1");
        fixed = fixed.replaceAll("(?i)\\b(background|border-color|border|box-shadow|color|opacity|transform|padding|margin|gap|width|height|min-width|min-height|max-width|max-height)(\\d)", "$1 $2");
        return fixed;
    }

    private static String injectPrototypeQualityStyles(String html, Platform platform) {
        if (html == null || html.isBlank() || html.contains("data-proto-design-system")) {
            return html;
        }
        // 唯一样式来源：平台扁平组件包（co-*），不叠加任何守卫，避免 !important 互相覆盖导致排版失控
        String designSystem = com.example.aidocumentplatform.ai.prompt.PrototypeDesignSystem.buildDesignSystemCss(platform);
        String lower = html.toLowerCase();
        int headClose = lower.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + designSystem + html.substring(headClose);
        }
        int bodyOpen = lower.indexOf("<body");
        if (bodyOpen >= 0) {
            int bodyTagEnd = html.indexOf('>', bodyOpen);
            if (bodyTagEnd > 0) {
                return html.substring(0, bodyTagEnd + 1) + designSystem + html.substring(bodyTagEnd + 1);
            }
        }
        return designSystem + html;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}