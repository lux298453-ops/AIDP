package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrototypeAiEditRequest;
import com.example.aidocumentplatform.model.dto.request.PrototypeAiEditSnapshotRequest;
import com.example.aidocumentplatform.model.dto.response.PrototypeAiEditResponse;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.service.PrototypeAiEditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link PrototypeAiEditService} 的实现：同步调用大模型完成一次原型修改。
 *
 * 流程：鉴权 → 取基准 HTML → 组装 prompt → 调 AI → 解析(说明 + 新HTML) → 单页原型落库 → 返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrototypeAiEditServiceImpl implements PrototypeAiEditService {

    private static final String MARK_SUMMARY = "@@SUMMARY@@";
    private static final String MARK_HTML = "@@HTML@@";

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final PrototypePromptTemplate promptTemplate;
    private final AiClient aiClient;
    private final TaskService taskService;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public PrototypeAiEditResponse edit(Long prototypeId, Long userId, PrototypeAiEditRequest request) {
        // 1. 加载 + 鉴权
        PrototypeResult proto = loadOwnedPrototype(prototypeId, userId);

        // 2. 确定基准 HTML：优先用前端传来的最新（含手动微调）内容，否则用库中内容
        String baseHtml = resolveBaseHtml(proto, request.getCurrentHtml());
        if (baseHtml == null || baseHtml.isBlank()) {
            throw new IllegalStateException("原型内容为空，无法修改");
        }

        // 3. 组装 prompt 并调用 AI
        String systemPrompt = promptTemplate.getEditSystemPrompt();
        String userPrompt = promptTemplate.buildEditPrompt(
                baseHtml, request.getInstruction(), request.getTargetElement());
        log.info("原型 AI 修改: id={}, instructionLen={}", prototypeId, request.getInstruction().length());
        String aiOutput;
        AiRequestContext.setUserId(userId);
        try {
            aiOutput = aiClient.generate(systemPrompt, userPrompt);
        } finally {
            AiRequestContext.clear();
        }

        // 4. 解析「说明 + 新 HTML」
        ParsedEdit parsed = parseOutput(aiOutput);
        String newHtml = cleanHtml(parsed.html);
        if (newHtml.isBlank()) {
            throw new RuntimeException("AI 未返回有效的 HTML，请调整描述后重试");
        }

        // 5. 落库。同步请求可能被前端超时断开，所以服务端必须保存结果，便于前端恢复刷新。
        saveEditedHtml(proto, newHtml, request.getPageIndex());

        return PrototypeAiEditResponse.builder()
                .prototypeId(prototypeId)
                .newHtml(newHtml)
                .changeSummary(parsed.summary)
                .build();
    }

    @Override
    public Long submitStreamEdit(Long prototypeId, Long userId, PrototypeAiEditRequest request) {
        loadOwnedPrototype(prototypeId, userId);

        IdempotentTaskService.TaskReservation reservation = idempotentTaskService.createOrReuseTask(
                userId, TaskType.PROTOTYPE_AI_EDIT, buildStreamInputParams(prototypeId, request));
        AsyncTask task = reservation.task();
        if (reservation.created()) {
            applicationContext.getBean(PrototypeAiEditServiceImpl.class)
                    .executeStreamEdit(task.getId(), prototypeId, userId, request);
        }
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void executeStreamEdit(Long taskId, Long prototypeId, Long userId, PrototypeAiEditRequest request) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) return;
        try {
            asyncTaskLifecycleService.markRunning(taskId);
            taskService.pushProgress(taskId, 10, "AI 正在分析原型修改需求...");

            PrototypeResult proto = loadOwnedPrototype(prototypeId, userId);
            String baseHtml = resolveBaseHtml(proto, request.getCurrentHtml());
            if (baseHtml == null || baseHtml.isBlank()) {
                throw new IllegalStateException("原型内容为空，无法修改");
            }

            String systemPrompt = promptTemplate.getEditPatchSystemPrompt();
            String userPrompt = promptTemplate.buildEditPatchPrompt(
                    baseHtml, request.getInstruction(), request.getTargetElement());
            PatchStreamAccumulator accumulator = new PatchStreamAccumulator(taskId);

            AiRequestContext.setUserId(userId);
            try {
                aiClient.generateStream(systemPrompt, userPrompt, accumulator::accept);
            } finally {
                AiRequestContext.clear();
            }
            accumulator.finish();

            if (accumulator.patchCount() == 0) {
                throw new RuntimeException("AI 未返回可执行的原型修改指令");
            }

            asyncTaskLifecycleService.markSuccess(taskId, prototypeId);
            String message = accumulator.summary().isBlank()
                    ? "原型修改完成"
                    : accumulator.summary();
            taskService.pushProgress(taskId, 100, message);
            log.info("原型 AI 流式修改成功: taskId={}, prototypeId={}, patchCount={}",
                    taskId, prototypeId, accumulator.patchCount());
        } catch (Exception e) {
            log.error("原型 AI 流式修改失败: taskId={}, prototypeId={}", taskId, prototypeId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
            taskService.pushProgress(taskId, 0, "AI 修改失败: " + e.getMessage());
        }
    }

    @Override
    public PrototypeAiEditResponse saveSnapshot(Long prototypeId, Long userId, PrototypeAiEditSnapshotRequest request) {
        PrototypeResult proto = loadOwnedPrototype(prototypeId, userId);
        String newHtml = cleanHtml(request.getCurrentHtml());
        if (newHtml.isBlank()) {
            throw new RuntimeException("原型 HTML 为空，无法保存");
        }
        saveEditedHtml(proto, newHtml, request.getPageIndex());
        return PrototypeAiEditResponse.builder()
                .prototypeId(prototypeId)
                .newHtml(newHtml)
                .changeSummary(notBlank(request.getChangeSummary(), "AI 已根据您的描述完成修改"))
                .build();
    }

    // ==================== 输出解析 ====================

    private record ParsedEdit(String summary, String html) {}

    private PrototypeResult loadOwnedPrototype(Long prototypeId, Long userId) {
        PrototypeResult proto = prototypeResultRepository.findById(prototypeId)
                .orElseThrow(() -> new RuntimeException("原型不存在"));
        if (!proto.getUserId().equals(userId)) {
            throw new RuntimeException("无权修改该原型");
        }
        return proto;
    }

    private String resolveBaseHtml(PrototypeResult proto, String currentHtml) {
        return currentHtml != null && !currentHtml.isBlank()
                ? currentHtml
                : unwrapJsonString(proto.getContent());
    }

    private String buildStreamInputParams(Long prototypeId, PrototypeAiEditRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("prototypeId", prototypeId);
        root.put("instruction", request.getInstruction());
        root.put("targetElement", request.getTargetElement());
        if (request.getPageIndex() != null) root.put("pageIndex", request.getPageIndex());
        root.put("hasCurrentHtml", request.getCurrentHtml() != null && !request.getCurrentHtml().isBlank());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void saveEditedHtml(PrototypeResult proto, String newHtml, Integer pageIndex) {
        if (proto.getPrototypeType() == PrototypeType.SINGLE_PAGE) {
            proto.setContent(newHtml);
            prototypeResultRepository.save(proto);
            return;
        }

        String content = proto.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("多页原型内容为空，无法保存当前页修改");
        }

        int idx = pageIndex != null && pageIndex >= 0 ? pageIndex : 0;
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root.isArray()) {
                ArrayNode pages = (ArrayNode) root.deepCopy();
                replacePageHtml(pages, idx, newHtml);
                proto.setContent(objectMapper.writeValueAsString(pages));
                prototypeResultRepository.save(proto);
                return;
            }
            if (root.has("pages") && root.get("pages").isArray()) {
                ObjectNode wrapper = (ObjectNode) root.deepCopy();
                ArrayNode pages = (ArrayNode) wrapper.get("pages");
                replacePageHtml(pages, idx, newHtml);
                proto.setContent(objectMapper.writeValueAsString(wrapper));
                prototypeResultRepository.save(proto);
                return;
            }
        } catch (Exception e) {
            throw new IllegalStateException("多页原型内容解析失败，无法保存当前页修改", e);
        }

        throw new IllegalStateException("多页原型内容格式不支持，无法保存当前页修改");
    }

    private void replacePageHtml(ArrayNode pages, int pageIndex, String newHtml) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            throw new IllegalArgumentException("当前页不存在，无法保存 AI 修改结果");
        }
        JsonNode page = pages.get(pageIndex);
        ObjectNode nextPage = page != null && page.isObject()
                ? (ObjectNode) page.deepCopy()
                : objectMapper.createObjectNode();
        nextPage.put("html", newHtml);
        pages.set(pageIndex, nextPage);
    }

    private ParsedEdit parseOutput(String out) {
        if (out == null) return new ParsedEdit("AI 已完成修改", "");
        int hIdx = out.indexOf(MARK_HTML);
        if (hIdx >= 0) {
            String head = out.substring(0, hIdx).replace(MARK_SUMMARY, "").trim();
            String html = out.substring(hIdx + MARK_HTML.length());
            String summary = head.isBlank() ? "AI 已根据您的描述完成修改" : head;
            return new ParsedEdit(summary, html);
        }
        // 未按约定输出分隔符：整段当作 HTML，给一个兜底说明
        return new ParsedEdit("AI 已根据您的描述完成修改", out);
    }

    /** 去除 markdown 代码块标记，并裁剪到首尾尖括号之间，得到纯 HTML */
    private String cleanHtml(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```html")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        int lt = s.indexOf('<'), end = s.lastIndexOf('>');
        if (lt > 0) s = s.substring(lt);
        if (end >= 0 && end < s.length() - 1) s = s.substring(0, end + 1);
        return s.trim();
    }

    private final class PatchStreamAccumulator {
        private final Long taskId;
        private final StringBuilder buffer = new StringBuilder();
        private int patchCount;
        private String summary = "";

        private PatchStreamAccumulator(Long taskId) {
            this.taskId = taskId;
        }

        void accept(String delta) {
            if (delta == null || delta.isBlank()) return;
            buffer.append(delta.replace("\r\n", "\n").replace('\r', '\n'));
            drain(false);
        }

        void finish() {
            drain(true);
        }

        int patchCount() {
            return patchCount;
        }

        String summary() {
            return summary;
        }

        private void drain(boolean flush) {
            while (true) {
                int start = firstObjectStart(buffer);
                if (start < 0) {
                    if (flush) buffer.setLength(0);
                    else trimNoisePrefix();
                    return;
                }
                if (start > 0) buffer.delete(0, start);

                int end = completeObjectEnd(buffer);
                if (end < 0) {
                    if (flush) buffer.setLength(0);
                    return;
                }

                String json = buffer.substring(0, end + 1);
                buffer.delete(0, end + 1);
                publishJson(json);
            }
        }

        private int firstObjectStart(CharSequence text) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '{') return i;
            }
            return -1;
        }

        private int completeObjectEnd(CharSequence text) {
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (ch == '\\') {
                        escape = true;
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }

                if (ch == '"') {
                    inString = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private void trimNoisePrefix() {
            if (buffer.length() <= 4096) return;
            buffer.delete(0, buffer.length() - 1024);
        }

        private void publishJson(String json) {
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root.has("patches") && root.get("patches").isArray()) {
                    for (JsonNode item : root.get("patches")) publishPatch(item);
                    return;
                }
                publishPatch(root);
            } catch (Exception e) {
                log.debug("忽略无法解析的原型 patch: {}", e.getMessage());
            }
        }

        private void publishPatch(JsonNode root) {
            Map<String, Object> patch = normalizePatch(root);
            if (patch == null) return;
            patch.put("seq", ++patchCount);
            Object text = patch.get("text");
            Object patchSummary = patch.get("summary");
            if (text instanceof String s && !s.isBlank()) summary = s;
            else if (patchSummary instanceof String s && !s.isBlank()) summary = s;
            taskService.pushCustomEvent(taskId, "prototype-patch", patch);
        }
    }

    private Map<String, Object> normalizePatch(JsonNode root) {
        if (root == null || !root.isObject()) return null;
        String op = normalizeOp(root.path("op").asText(""));
        if (op.isBlank()) return null;

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", op);

        if ("summary".equals(op)) {
            String text = notBlank(root.path("text").asText(""), root.path("summary").asText(""));
            if (text.isBlank()) text = "AI 已根据您的描述完成修改";
            patch.put("text", truncate(text, 240));
            return patch;
        }

        String selector = root.path("selector").asText("");
        if (selector.isBlank()) return null;
        patch.put("selector", selector);
        patch.put("all", root.path("all").asBoolean(false));

        String summaryText = root.path("summary").asText("");
        if (!summaryText.isBlank()) patch.put("summary", truncate(summaryText, 240));

        switch (op) {
            case "setStyle" -> {
                String property = root.path("property").asText("");
                String value = root.path("value").asText("");
                if (property.isBlank() || value.isBlank()) return null;
                patch.put("property", property);
                patch.put("value", value);
            }
            case "setText" -> {
                String value = root.path("value").asText("");
                if (value.isBlank()) value = root.path("text").asText("");
                patch.put("value", value);
            }
            case "setAttr" -> {
                String name = root.path("name").asText("");
                String value = root.path("value").asText("");
                if (name.isBlank()) return null;
                patch.put("name", name);
                patch.put("value", value);
            }
            case "addClass", "removeClass" -> {
                String value = root.path("value").asText("");
                if (value.isBlank()) value = root.path("className").asText("");
                if (value.isBlank()) return null;
                patch.put("value", value);
            }
            case "insertHtml" -> {
                String html = root.path("html").asText("");
                if (html.isBlank()) return null;
                patch.put("position", normalizeInsertPosition(root.path("position").asText("beforeend")));
                patch.put("html", html);
            }
            case "replaceHtml" -> {
                String html = root.path("html").asText("");
                if (html.isBlank()) return null;
                patch.put("html", html);
            }
            case "remove" -> {
                // selector 足够描述删除目标。
            }
            default -> {
                return null;
            }
        }
        return patch;
    }

    private String normalizeOp(String raw) {
        String op = raw == null ? "" : raw.trim();
        return switch (op) {
            case "set_style", "style", "css" -> "setStyle";
            case "set_text", "text" -> "setText";
            case "set_attr", "attr" -> "setAttr";
            case "add_class" -> "addClass";
            case "remove_class" -> "removeClass";
            case "insert", "insert_html" -> "insertHtml";
            case "replace", "replace_html" -> "replaceHtml";
            case "delete" -> "remove";
            case "summary" -> "summary";
            default -> op;
        };
    }

    private String normalizeInsertPosition(String raw) {
        return switch (raw == null ? "" : raw.trim()) {
            case "beforebegin", "afterbegin", "afterend" -> raw.trim();
            default -> "beforeend";
        };
    }

    /** 递归解开可能多层 JSON 编码的字符串，直到得到纯 HTML/文本 */
    private String unwrapJsonString(String value) {
        if (value == null) return "";
        String s = value.trim();
        for (int i = 0; i < 5; i++) {
            if (s.startsWith("\"") && s.endsWith("\"")) {
                try {
                    s = objectMapper.readValue(s, String.class);
                    if (s == null) break;
                } catch (Exception e) { break; }
            } else {
                try {
                    JsonNode node = objectMapper.readTree(s);
                    if (node.isTextual()) { s = node.asText(); continue; }
                } catch (Exception ignored) { /* 非 JSON，视为纯 HTML */ }
                break;
            }
        }
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String notBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}