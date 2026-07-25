package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.util.JsonUtils;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
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
    private final TaskServiceImpl taskService;
    private final ApplicationContext applicationContext;

    @Override
    public Long submit(PrototypeGenerateRequest request, Long userId) {
        boolean hasRef = request.getReferenceImageBase64() != null
                && !request.getReferenceImageBase64().isBlank();
        StringBuilder input = new StringBuilder();
        input.append("{\"platform\":\"").append(request.getPlatform().name()).append("\"")
                .append(",\"prototypeType\":\"").append(request.getPrototypeType().name()).append("\"")
                .append(",\"description\":\"").append(JsonUtils.escapeJsonString(request.getDescription())).append("\"")
                .append(",\"hasReferenceImage\":").append(hasRef);
        if (hasRef && request.getReferenceImageFileName() != null) {
            input.append(",\"referenceImageFileName\":\"")
                    .append(JsonUtils.escapeJsonString(request.getReferenceImageFileName())).append("\"");
        }
        if (request.getReferenceImagePath() != null) {
            input.append(",\"referenceImagePath\":\"")
                    .append(JsonUtils.escapeJsonString(request.getReferenceImagePath())).append("\"");
        }
        input.append("}");

        AsyncTask task = AsyncTask.builder()
                .userId(userId).taskType(TaskType.PROTOTYPE).status(TaskStatus.PENDING)
                .inputParams(input.toString())
                .build();
        task = asyncTaskRepository.save(task);
        log.info("原型生成任务: taskId={}, type={}, platform={}, hasRef={}",
                task.getId(), request.getPrototypeType(), request.getPlatform(), hasRef);
        applicationContext.getBean(PrototypeGenerateServiceImpl.class)
                .execute(task.getId(), request, userId);
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, PrototypeGenerateRequest request, Long userId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus(TaskStatus.RUNNING); asyncTaskRepository.save(task);
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
                    request.getReferenceImageFileName());

            String aiOutput;
            AiRequestContext.setUserId(userId);
            try {
                if (hasRef) {
                    aiOutput = aiClient.generateWithImage(
                            systemPrompt,
                            userPrompt,
                            request.getReferenceImageMimeType(),
                            request.getReferenceImageBase64());
                } else {
                    aiOutput = aiClient.generate(systemPrompt, userPrompt);
                }
            } finally {
                AiRequestContext.clear();
            }

            // 先清洗/落库再推 SSE，避免进度推送异常影响保存
            String content = request.getPrototypeType() == PrototypeType.MULTI_PAGE
                    ? cleanJson(aiOutput)
                    : cleanHtml(aiOutput);

            PrototypeResult proto = PrototypeResult.builder()
                    .userId(userId).taskId(taskId).prdDocumentId(request.getPrdDocumentId())
                    .prototypeType(request.getPrototypeType()).platform(request.getPlatform())
                    .content(content).build();
            proto = prototypeResultRepository.save(proto);

            task.setResultRefId(proto.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 100, "原型生成完成");
            log.info("原型生成成功: taskId={}, resultId={}, type={}, hasRef={}, len={}",
                    taskId, proto.getId(), request.getPrototypeType(), hasRef, content.length());

        } catch (Exception e) {
            log.error("原型生成失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 清洗模型输出为可预览 HTML。
     * Grok 等模型常夹带 markdown / 思考段落 / 不完整文档，这里尽量抽出完整 HTML，
     * 并对「只有 class、几乎没有 CSS」的结果注入基础样式，避免预览完全裸奔。
     */
    private String cleanHtml(String raw) {
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

        // 模型只吐了片段时，包一层最小文档结构
        if (!looksLikeFullDocument(s)) {
            s = wrapAsDocument(s);
        }

        // 几乎没有 CSS 时补基础样式，避免“全是裸标签”
        if (!hasSubstantialCss(s)) {
            s = injectBaseStyles(s);
            log.warn("原型 HTML 缺少有效 CSS，已注入基础样式兜底, len={}", s.length());
        }
        return s;
    }

    /**
     * 多页 JSON：清洗数组，并对每个 page.html 再走 cleanHtml，
     * 避免多页场景下样式缺失 / 文档结构不完整。
     */
    private String cleanJson(String raw) {
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
                page.put("html", cleanHtml(html));
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

    private static boolean hasSubstantialCss(String html) {
        String lower = html.toLowerCase();
        int styleOpen = lower.indexOf("<style");
        if (styleOpen < 0) {
            // 没有任何 style，但有大量 class 时视为样式不足
            return !lower.contains("class=");
        }
        int styleClose = lower.indexOf("</style>", styleOpen);
        if (styleClose < 0) return false;
        String css = html.substring(styleOpen, styleClose);
        // 过滤掉几乎空的 style
        String compact = css.replaceAll("\\s+", "");
        return compact.length() > 80 && (compact.contains("{") || compact.contains(":"));
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

    private static String injectBaseStyles(String html) {
        String base = """
                <style data-proto-fallback>
                *,*::before,*::after{box-sizing:border-box}
                body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,"Noto Sans SC",sans-serif;background:#f5f7fa;color:#303133;line-height:1.6}
                a{color:#409EFF;text-decoration:none}
                button,.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:8px 16px;border:none;border-radius:8px;background:#409EFF;color:#fff;font-size:14px;cursor:pointer}
                button:hover,.btn:hover{filter:brightness(.95)}
                input,select,textarea{width:100%;padding:8px 12px;border:1px solid #dcdfe6;border-radius:8px;font-size:14px;background:#fff}
                .card,section,main{background:#fff;border-radius:8px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
                header,nav,.header,.navbar{background:#fff;border-bottom:1px solid #e4e7ed}
                .container,.page,.app{max-width:1200px;margin:0 auto;padding:16px}
                h1,h2,h3{margin:0 0 12px;font-weight:600}
                ul{padding-left:18px}
                table{width:100%;border-collapse:collapse}
                th,td{border-bottom:1px solid #ebeef5;padding:10px 8px;text-align:left}
                </style>
                """;
        String lower = html.toLowerCase();
        int headClose = lower.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + base + html.substring(headClose);
        }
        int bodyOpen = lower.indexOf("<body");
        if (bodyOpen >= 0) {
            int bodyTagEnd = html.indexOf('>', bodyOpen);
            if (bodyTagEnd > 0) {
                return html.substring(0, bodyTagEnd + 1) + base + html.substring(bodyTagEnd + 1);
            }
        }
        return base + html;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
