package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrdEnhancePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrdEnhanceRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.enums.DocumentSourceType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.service.PrdEnhanceService;
import com.example.aidocumentplatform.util.WordReader;
import com.example.aidocumentplatform.util.PrdContentParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrdEnhanceServiceImpl implements PrdEnhanceService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final AiClient aiClient;
    private final PrdEnhancePromptTemplate promptTemplate;
    private final TaskService taskService;
    private final WordReader wordReader;
    private final PrdContentParser prdContentParser;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Long submit(PrdEnhanceRequest request, Long userId) {
        String prdContent = resolvePrdContent(request, userId);
        IdempotentTaskService.TaskReservation reservation =
                createTask(userId, prdContent, request.getContentTypes(), null);
        // 经 Spring 代理调用，确保 @Async 生效
        if (reservation.created()) {
            applicationContext.getBean(PrdEnhanceServiceImpl.class).execute(
                    reservation.task().getId(), prdContent, null, request.getContentTypes(), request.getInstruction(),
                    request.getPrdDocumentId(), userId);
        }
        return reservation.task().getId();
    }

    @Override
    public Long submitWithWord(PrdEnhanceRequest request, byte[] wordBytes, String fileName, Long userId) {
        String wordContent = wordReader.extractText(wordBytes);
        String prdContent = resolvePrdContent(request, userId);
        IdempotentTaskService.TaskReservation reservation =
                createTask(userId, prdContent, request.getContentTypes(), fileName);
        if (reservation.created()) {
            applicationContext.getBean(PrdEnhanceServiceImpl.class).execute(
                    reservation.task().getId(), prdContent, wordContent, request.getContentTypes(), request.getInstruction(),
                    request.getPrdDocumentId(), userId);
        }
        return reservation.task().getId();
    }

    /** 解析 PRD 内容：优先从 prdDocumentId 查，否则用 prdContent */
    private String resolvePrdContent(PrdEnhanceRequest req, Long userId) {
        if (req.getPrdDocumentId() != null) {
            PrdDocument source = findOwnedPrd(req.getPrdDocumentId(), userId);
            return source.getTitle() + "\n" + (source.getDescription() != null ? source.getDescription() + "\n" : "")
                    + "\n【结构化 PRD 内容】\n" + source.getContent();
        }
        return req.getPrdContent();
    }

    private IdempotentTaskService.TaskReservation createTask(Long userId, String prdContent, List<String> types, String fileName) {
        // List.toString() 会生成 [structure, flow]（无引号），PostgreSQL JSONB 不接受；必须用 ObjectMapper
        String inputJson;
        try {
            ObjectNode node = objectMapper.createObjectNode();
            ArrayNode arr = node.putArray("contentTypes");
            if (types != null) {
                for (String t : types) arr.add(t);
            }
            if (fileName != null) node.put("wordFile", fileName);
            // 截断保存一份原文快照，便于 regenerate（可选）
            if (prdContent != null && !prdContent.isBlank()) {
                node.put("prdContent", prdContent.length() > 4000 ? prdContent.substring(0, 4000) : prdContent);
            }
            inputJson = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            inputJson = "{\"contentTypes\":[]}";
            log.warn("序列化增强任务 inputParams 失败，使用空 contentTypes", e);
        }
        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PRD_ENHANCE, inputJson);
        AsyncTask task = reservation.task();
        log.info("PRD增强任务已创建: taskId={}, userId={}, contentTypes={}", task.getId(), userId, types);
        return reservation;
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, String prdContent, String wordContent,
                         List<String> contentTypes, String instruction, Long sourceDocumentId, Long userId) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) return;
        try {
            asyncTaskLifecycleService.markRunning(taskId);
            List<String> types = contentTypes != null ? contentTypes : List.of();
            boolean needChart = types.stream().anyMatch(t ->
                    "structure".equalsIgnoreCase(t) || "flow".equalsIgnoreCase(t));
            taskService.pushProgress(taskId, 10,
                    needChart ? "AI 正在生成增强内容与图表..." : "AI 正在生成增强内容...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(prdContent, wordContent, types, instruction);
            log.info("PRD增强 AI 调用: taskId={}, types={}, promptLen={}", taskId, types, userPrompt.length());
            String aiResponse;
            AiRequestContext.setUserId(userId);
            try {
                aiResponse = aiClient.generateStream(systemPrompt, userPrompt,
                        delta -> taskService.pushContentDelta(taskId, delta));
            } finally {
                AiRequestContext.clear();
            }

            // 先落库再推 SSE，避免进度推送异常影响保存
            PrdDocument source = sourceDocumentId != null ? findOwnedPrd(sourceDocumentId, userId) : null;
            String jsonContent = buildEnhancedPrd(aiResponse, source, types);
            publishEnhanceWarnings(taskId, jsonContent, types);

            // 存入 prd_document（增强结果也作为 PRD 文档的一条记录）
            PrdDocument doc = PrdDocument.builder()
                    .userId(userId).taskId(taskId)
                    .title(source != null ? source.getTitle() + "（增强版）" : "增强结果")
                    .description("原始内容长度: " + (prdContent != null ? prdContent.length() : 0))
                    .content(jsonContent)
                    .sourceType(DocumentSourceType.MANUAL)
                    .template(source != null ? source.getTemplate() : null)
                    .detailLevel(source != null ? source.getDetailLevel() : null)
                    .build();
            doc = asyncTaskLifecycleService.savePrdDocumentAndMarkSuccess(taskId, doc);
            taskService.pushProgress(taskId, 100, "PRD 增强完成");
            log.info("PRD增强成功: taskId={}, documentId={}", taskId, doc.getId());
        } catch (Exception e) {
            log.error("PRD增强失败: taskId={}", taskId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
            taskService.pushProgress(taskId, 0, "增强失败: " + e.getMessage());
        }
    }

    private PrdDocument findOwnedPrd(Long id, Long userId) {
        PrdDocument document = prdDocumentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        if (!document.getUserId().equals(userId)) throw new IllegalArgumentException("无权访问该 PRD");
        return document;
    }

    private String buildEnhancedPrd(String aiResponse, PrdDocument source, List<String> contentTypes) {
        JsonNode generated = prdContentParser.parseObject(aiResponse);
        if (generated.path("chapters").isArray()) {
            // 已是完整 PRD 结构时，仍规范化各章图表围栏
            ObjectNode root = generated.deepCopy();
            ArrayNode chapters = (ArrayNode) root.path("chapters");
            for (int i = 0; i < chapters.size(); i++) {
                ObjectNode ch = (ObjectNode) chapters.get(i);
                String type = ch.path("type").asText("");
                String content = normalizeChartFences(ch.path("content").asText(""));
                if (("structure".equals(type) || "flow".equals(type)) && !containsChart(content)) {
                    log.warn("增强章节缺少图表: type={}, title={}", type, ch.path("title").asText());
                    content = content + "\n\n> 提示：本应包含图表（PlantUML），但模型未返回有效图源，请重新生成或手动补充。\n";
                }
                ch.put("content", content);
            }
            return root.toString();
        }

        ObjectNode root = source != null
                ? prdContentParser.normalize(source.getContent()).deepCopy()
                : objectMapper.createObjectNode();
        if (!root.hasNonNull("title")) root.put("title", source != null ? source.getTitle() : "增强结果");
        if (!root.hasNonNull("summary")) root.put("summary", "基于原 PRD 生成的增强版本");

        ArrayNode chapters = root.withArray("chapters");
        JsonNode sections = generated.path("sections");
        if (!sections.isArray()) throw new IllegalArgumentException("增强结果缺少 sections 或 chapters 数组");
        for (JsonNode section : sections) {
            ObjectNode chapter = objectMapper.createObjectNode();
            String type = section.path("type").asText("enhancement");
            String title = section.path("title").asText(defaultTitle(type));
            String content = normalizeChartFences(section.path("content").asText(""));
            if (("structure".equals(type) || "flow".equals(type)) && !containsChart(content)) {
                log.warn("增强章节缺少图表: type={}, title={}", type, title);
                content = content + "\n\n> 提示：本应包含图表（PlantUML），但模型未返回有效图源，请重新生成或手动补充。\n";
            }
            chapter.put("title", title);
            chapter.put("content", content);
            chapter.put("type", type);
            chapters.add(chapter);
        }

        // 若请求了 structure/flow 但 AI 完全没返回对应 section，记日志（不阻断）
        if (contentTypes != null) {
            for (String t : contentTypes) {
                if (!"structure".equals(t) && !"flow".equals(t)) continue;
                boolean found = false;
                for (JsonNode ch : chapters) {
                    if (t.equals(ch.path("type").asText())) { found = true; break; }
                }
                if (!found) log.warn("请求了 {} 但增强结果中无对应章节", t);
            }
        }
        return root.toString();
    }

    private void publishEnhanceWarnings(Long taskId, String jsonContent, List<String> requestedTypes) {
        if (taskId == null || jsonContent == null || jsonContent.isBlank()) {
            return;
        }
        try {
            JsonNode root = prdContentParser.parseObject(jsonContent);
            JsonNode chapters = root.path("chapters");
            if (!chapters.isArray()) {
                return;
            }

            java.util.List<String> warnings = new java.util.ArrayList<>();
            if (requestedTypes != null) {
                for (String type : requestedTypes) {
                    if (!"structure".equals(type) && !"flow".equals(type)) {
                        continue;
                    }
                    boolean found = false;
                    for (JsonNode chapter : chapters) {
                        if (type.equals(chapter.path("type").asText())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        warnings.add("requested chapter type missing: " + type);
                    }
                }
            }

            for (JsonNode chapter : chapters) {
                String type = chapter.path("type").asText("");
                if (("structure".equals(type) || "flow".equals(type))
                        && !containsChart(chapter.path("content").asText(""))) {
                    warnings.add("chart source missing for chapter type: " + type);
                }
            }

            for (String warning : warnings) {
                log.warn("PRD enhancement degraded result: taskId={}, warning={}", taskId, warning);
                taskService.pushCustomEvent(taskId, "enhance-warning", java.util.Map.of(
                        "taskId", taskId,
                        "message", warning
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to inspect enhanced PRD result: taskId={}, cause={}", taskId, e.getMessage());
        }
    }
    private String defaultTitle(String type) {
        return switch (type) {
            case "structure" -> "页面结构图";
            case "flow" -> "流程图";
            case "data" -> "数据字段";
            case "testcase" -> "测试用例";
            default -> "增强内容";
        };
    }

    /** 是否包含图表源码（PlantUML 围栏/裸 @startuml 块，或 Mermaid 围栏/裸 flowchart） */
    private boolean containsChart(String content) {
        if (content == null || content.isBlank()) return false;
        String c = content.toLowerCase();
        return c.contains("```plantuml")
                || c.contains("@startuml")
                || c.contains("```mermaid")
                || c.contains("flowchart ")
                || c.contains("sequencediagram")
                || c.contains("graph td")
                || c.contains("graph lr");
    }

    /**
     * 规范化 AI 可能输出的不完整图表围栏：
     * - 若出现 @startuml 但未包 ```plantuml，自动包一层
     * - 若出现 flowchart/sequenceDiagram 但未包 ```mermaid，自动包一层
     */
    private String normalizeChartFences(String content) {
        if (content == null || content.isBlank()) return content == null ? "" : content;
        String text = content.replace("\r\n", "\n");
        if (text.contains("```plantuml") || text.contains("```mermaid")) return text;

        // 尝试把裸 @startuml 块包进 ```plantuml 围栏
        String wrapped = wrapNakedPlantUml(text);
        if (!wrapped.equals(text)) return wrapped;

        // 再尝试把裸 flowchart / sequenceDiagram 块包进围栏
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inChart = false;
        StringBuilder chart = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            boolean start = !inChart && (
                    trimmed.matches("(?i)^(flowchart|graph|sequencediagram)\\b.*")
            );
            if (start) {
                inChart = true;
                chart.setLength(0);
                chart.append(line).append('\n');
                continue;
            }
            if (inChart) {
                // 空行 + 下一非缩进中文说明 → 结束图
                if (trimmed.isEmpty()) {
                    chart.append(line).append('\n');
                    continue;
                }
                if (trimmed.startsWith("```")) {
                    inChart = false;
                    out.append("```mermaid\n").append(chart).append("```\n");
                    chart.setLength(0);
                    continue;
                }
                // 看起来仍是 mermaid 语法行
                if (trimmed.matches(".*(--|==>|-->|\\[|\\]|\\(|\\)|subgraph|end|participant|Note).*")
                        || trimmed.matches("^[A-Za-z][\\w]*([\\[{].*)?$")
                        || trimmed.matches("(?i)^(style|classDef|click|linkStyle)\\b.*")) {
                    chart.append(line).append('\n');
                    continue;
                }
                // 结束图表
                out.append("```mermaid\n").append(chart.toString().stripTrailing()).append("\n```\n\n");
                chart.setLength(0);
                inChart = false;
                out.append(line).append('\n');
                continue;
            }
            out.append(line).append('\n');
        }
        if (inChart && chart.length() > 0) {
            out.append("```mermaid\n").append(chart.toString().stripTrailing()).append("\n```\n");
        }
        return out.toString().stripTrailing();
    }

    /** 把裸 @startuml ... @enduml 包进 ```plantuml 围栏（保留围栏外文字） */
    private String wrapNakedPlantUml(String text) {
        if (!text.contains("@startuml")) return text;
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        StringBuilder block = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.equalsIgnoreCase("@startuml") || t.toLowerCase().startsWith("@startuml")) {
                if (!inBlock) {
                    inBlock = true;
                    block.setLength(0);
                    block.append(line).append('\n');
                    continue;
                }
            }
            if (inBlock) {
                block.append(line).append('\n');
                if (t.equalsIgnoreCase("@enduml") || t.toLowerCase().startsWith("@enduml")) {
                    inBlock = false;
                    out.append("```plantuml\n").append(block.toString().stripTrailing()).append("\n```\n\n");
                    block.setLength(0);
                }
                continue;
            }
            out.append(line).append('\n');
        }
        if (inBlock && block.length() > 0) {
            out.append("```plantuml\n").append(block.toString().stripTrailing()).append("\n```\n");
        }
        return out.toString().stripTrailing();
    }

    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }
}