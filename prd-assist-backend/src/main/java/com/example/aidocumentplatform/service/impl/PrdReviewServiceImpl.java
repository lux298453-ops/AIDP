package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrdReviewFixPromptTemplate;
import com.example.aidocumentplatform.ai.prompt.PrdReviewPromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrdReviewFixRequest;
import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.model.enums.DetailLevel;
import com.example.aidocumentplatform.model.enums.DocumentSourceType;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.model.enums.TemplateType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.service.PrdReviewService;
import com.example.aidocumentplatform.util.JsonUtils;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrdReviewServiceImpl implements PrdReviewService {

    private static final Set<String> DEFAULT_FIX_SEVERITIES = Set.of("CRITICAL", "MAJOR");

    private final AsyncTaskRepository asyncTaskRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final AiClient aiClient;
    private final PrdReviewPromptTemplate promptTemplate;
    private final PrdReviewFixPromptTemplate fixPromptTemplate;
    private final TaskServiceImpl taskService;
    private final PrdContentParser prdContentParser;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ======================== 提交审查 ========================

    @Override
    public Long submit(PrdReviewRequest request, Long userId) {
        // 解析/落库 PRD，保证始终有有效 prdDocumentId
        ResolvedPrd resolved = resolveOrCreatePrd(request, userId);

        List<String> dims = request.getDimensions() != null ? request.getDimensions() : List.of();
        String dimsJson = toJson(dims);

        String inputParams = "{\"prdDocumentId\":" + resolved.prdDocId()
                + ",\"dimensions\":" + dimsJson
                + ",\"requirement\":\"" + JsonUtils.escapeJsonString(
                request.getRequirement() != null ? request.getRequirement() : "") + "\""
                + ",\"prdContent\":\"" + JsonUtils.escapeJsonString(truncate(resolved.reviewText(), 4000)) + "\"}";

        AsyncTask task = AsyncTask.builder()
                .userId(userId)
                .taskType(TaskType.PRD_REVIEW)
                .status(TaskStatus.PENDING)
                .inputParams(inputParams)
                .build();
        task = asyncTaskRepository.save(task);
        log.info("PRD审查任务已创建: taskId={}, userId={}, prdDocId={}, dimensions={}",
                task.getId(), userId, resolved.prdDocId(), dims);

        applicationContext.getBean(PrdReviewServiceImpl.class)
                .execute(task.getId(), resolved.reviewText(), dims, request.getRequirement(),
                        userId, resolved.prdDocId(), resolved.contentJson());
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, String prdContent, List<String> dimensions, String requirement,
                        Long userId, Long prdDocId, String contentJson) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus(TaskStatus.RUNNING);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 10, "AI 正在审查 PRD...");

            List<ChapterAnchor> chapterAnchors = extractChapterAnchors(contentJson);
            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    prdContent, dimensions, requirement, buildChapterOutline(chapterAnchors));
            String aiResponse;
            AiRequestContext.setUserId(userId);
            try {
                aiResponse = aiClient.generate(systemPrompt, userPrompt);
            } finally {
                AiRequestContext.clear();
            }

            // 先落库再推 SSE，避免进度推送异常影响保存
            JsonNode parsed = prdContentParser.parseObject(aiResponse);
            String json = enrichIssuesWithChapterAnchors(parsed, chapterAnchors).toString();
            String dimsJson = toJson(dimensions != null ? dimensions : List.of());

            ReviewReport report = ReviewReport.builder()
                    .userId(userId)
                    .prdDocumentId(prdDocId)
                    .taskId(taskId)
                    .dimensions(dimsJson)
                    .issues(json)
                    .build();
            report = reviewReportRepository.save(report);

            task.setResultRefId(report.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 100, "审查完成");
            log.info("PRD审查成功: taskId={}, reportId={}, prdDocId={}", taskId, report.getId(), prdDocId);
        } catch (Exception e) {
            log.error("PRD审查失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "审查失败: " + e.getMessage());
        }
    }

    // ======================== AI 修复 ========================

    @Override
    public Long submitFix(Long reportId, PrdReviewFixRequest request, Long userId) {
        ReviewReport report = reviewReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("审查报告不存在"));
        if (!report.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该审查报告");
        }
        if (report.getPrdDocumentId() == null || report.getPrdDocumentId() <= 0) {
            throw new IllegalArgumentException("该报告未关联 PRD 文档，无法修复");
        }

        PrdDocument source = prdDocumentRepository.findById(report.getPrdDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("关联的 PRD 不存在"));
        if (!source.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该 PRD");
        }

        List<JsonNode> selectedIssues = selectIssues(report.getIssues(), request);
        if (selectedIssues.isEmpty()) {
            throw new IllegalArgumentException("没有可修复的问题，请勾选问题或选择严重程度");
        }

        String issuesJson = toJson(selectedIssues);
        String inputParams = "{\"reportId\":" + reportId
                + ",\"sourcePrdId\":" + source.getId()
                + ",\"issueCount\":" + selectedIssues.size() + "}";

        AsyncTask task = AsyncTask.builder()
                .userId(userId)
                .taskType(TaskType.PRD_REVIEW_FIX)
                .status(TaskStatus.PENDING)
                .inputParams(inputParams)
                .build();
        task = asyncTaskRepository.save(task);
        log.info("PRD审查修复任务已创建: taskId={}, reportId={}, issues={}",
                task.getId(), reportId, selectedIssues.size());

        applicationContext.getBean(PrdReviewServiceImpl.class)
                .executeFix(task.getId(), source, issuesJson, userId);
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void executeFix(Long taskId, PrdDocument source, String issuesJson, Long userId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus(TaskStatus.RUNNING);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 10, "AI 正在根据审查意见修订 PRD...");

            String systemPrompt = fixPromptTemplate.getSystemPrompt();
            String userPrompt = fixPromptTemplate.buildUserPrompt(source.getContent(), issuesJson);
            String aiResponse;
            AiRequestContext.setUserId(userId);
            try {
                aiResponse = aiClient.generate(systemPrompt, userPrompt);
            } finally {
                AiRequestContext.clear();
            }

            // 先落库再推 SSE，避免进度推送异常影响保存
            String jsonContent = prdContentParser.normalizeToJson(aiResponse);

            // 解析新标题（可选）
            String newTitle = source.getTitle();
            try {
                JsonNode root = objectMapper.readTree(jsonContent);
                String t = root.path("title").asText("");
                if (!t.isBlank()) {
                    newTitle = t.length() > 50 ? t.substring(0, 50) : t;
                }
            } catch (Exception ignored) { /* keep original title */ }

            // 新版本，不覆盖原文档
            String versionSuffix = "（审查修订 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "）";
            String titleWithVersion = newTitle;
            if (!titleWithVersion.contains("审查修订")) {
                String base = titleWithVersion.length() > 30 ? titleWithVersion.substring(0, 30) : titleWithVersion;
                titleWithVersion = base + versionSuffix;
                if (titleWithVersion.length() > 50) titleWithVersion = titleWithVersion.substring(0, 50);
            }

            PrdDocument fixed = PrdDocument.builder()
                    .userId(userId)
                    .taskId(taskId)
                    .title(titleWithVersion)
                    .description(source.getDescription())
                    .content(jsonContent)
                    .sourceType(source.getSourceType() != null ? source.getSourceType() : DocumentSourceType.MANUAL)
                    .template(source.getTemplate() != null ? source.getTemplate() : TemplateType.STANDARD)
                    .detailLevel(source.getDetailLevel() != null ? source.getDetailLevel() : DetailLevel.DETAILED)
                    .build();
            fixed = prdDocumentRepository.save(fixed);

            task.setResultRefId(fixed.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 100, "修订完成");
            log.info("PRD审查修复成功: taskId={}, newPrdId={}, sourcePrdId={}",
                    taskId, fixed.getId(), source.getId());
        } catch (Exception e) {
            log.error("PRD审查修复失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "修复失败: " + e.getMessage());
        }
    }

    // ======================== 解析 / 落库 ========================

    private record ResolvedPrd(Long prdDocId, String reviewText, String contentJson) {}

    private record ChapterAnchor(int index, String title, String normalizedTitle, String number) {}

    /**
     * 已有文档：直接读取；粘贴：自动创建 prd_document。
     */
    private ResolvedPrd resolveOrCreatePrd(PrdReviewRequest req, Long userId) {
        if (req.getPrdDocumentId() != null) {
            PrdDocument document = prdDocumentRepository.findById(req.getPrdDocumentId())
                    .orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
            if (!document.getUserId().equals(userId)) {
                throw new IllegalArgumentException("无权访问该 PRD");
            }
            String text = document.getTitle() + "\n"
                    + (document.getDescription() != null ? document.getDescription() + "\n" : "")
                    + "\n【结构化 PRD 内容】\n" + document.getContent();
            return new ResolvedPrd(document.getId(), text, document.getContent());
        }

        String paste = req.getPrdContent();
        if (paste == null || paste.isBlank()) {
            throw new IllegalArgumentException("请粘贴 PRD 内容或选择已有文档");
        }

        // 自动落库，保证审查闭环可回写/导出
        String title = extractTitle(paste);
        String contentJson = wrapPasteAsPrdJson(title, paste);
        String description = paste.length() > 50000 ? paste.substring(0, 49997) + "..." : paste;

        PrdDocument doc = PrdDocument.builder()
                .userId(userId)
                .title(title)
                .description(description)
                .content(contentJson)
                .sourceType(DocumentSourceType.MANUAL)
                .template(TemplateType.STANDARD)
                .detailLevel(DetailLevel.DETAILED)
                .build();
        doc = prdDocumentRepository.save(doc);
        log.info("粘贴审查自动落库 PRD: prdDocId={}, title={}", doc.getId(), title);

        String text = title + "\n\n【PRD 内容】\n" + paste;
        return new ResolvedPrd(doc.getId(), text, contentJson);
    }

    private String extractTitle(String paste) {
        String firstLine = paste.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("审查文档");
        // 去掉 markdown 标题符号
        firstLine = firstLine.replaceAll("^#+\\s*", "").trim();
        if (firstLine.length() > 40) firstLine = firstLine.substring(0, 40);
        if (firstLine.isBlank()) firstLine = "审查文档";
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        String title = firstLine + "（审查 " + stamp + "）";
        return title.length() > 50 ? title.substring(0, 50) : title;
    }

    /** 将粘贴纯文本包装为标准 PRD JSON，便于编辑器/导出复用 */
    private String wrapPasteAsPrdJson(String title, String paste) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", title);
        // summary：取前 120 字
        String summary = paste.replaceAll("\\s+", " ").trim();
        if (summary.length() > 120) summary = summary.substring(0, 120) + "…";
        root.put("summary", summary);

        ArrayNode chapters = root.putArray("chapters");
        // 尝试按 markdown 标题或「数字. 标题」拆章
        String[] lines = paste.replace("\r\n", "\n").split("\n");
        String currentTitle = "正文";
        StringBuilder currentBody = new StringBuilder();
        boolean hasSplit = false;

        for (String line : lines) {
            if (line.matches("^\\s*#{1,3}\\s+.+") || line.matches("^\\s*\\d+(\\.\\d+)*[\\.、\\s）.].{1,60}$")) {
                if (currentBody.length() > 0 || hasSplit) {
                    ObjectNode ch = chapters.addObject();
                    ch.put("title", currentTitle);
                    ch.put("content", currentBody.toString().trim());
                    currentBody.setLength(0);
                }
                String t = line.replaceAll("^\\s*#{1,3}\\s*", "").trim();
                currentTitle = t.isEmpty() ? "未命名章节" : t;
                hasSplit = true;
            } else {
                if (currentBody.length() > 0) currentBody.append('\n');
                currentBody.append(line);
            }
        }
        ObjectNode last = chapters.addObject();
        last.put("title", currentTitle);
        last.put("content", currentBody.toString().trim());

        // 若拆章失败（只有空章），合成单章
        if (chapters.size() == 0
                || (chapters.size() == 1 && chapters.get(0).path("content").asText("").isBlank())) {
            chapters.removeAll();
            ObjectNode ch = chapters.addObject();
            ch.put("title", "正文");
            ch.put("content", paste);
        }

        return root.toString();
    }

    private List<ChapterAnchor> extractChapterAnchors(String contentJson) {
        List<ChapterAnchor> anchors = new ArrayList<>();
        if (contentJson == null || contentJson.isBlank()) return anchors;
        try {
            JsonNode root = prdContentParser.normalize(contentJson);
            JsonNode chapters = root.path("chapters");
            if (!chapters.isArray()) return anchors;
            for (int i = 0; i < chapters.size(); i++) {
                String title = chapters.get(i).path("title").asText("").trim();
                if (title.isBlank()) title = "未命名章节 " + (i + 1);
                String number = "";
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\s*(\\d+(?:\\.\\d+)*)")
                        .matcher(title);
                if (m.find()) number = m.group(1);
                anchors.add(new ChapterAnchor(i, title, normalizeAnchorText(title), number));
            }
        } catch (Exception e) {
            log.warn("提取 PRD 章节锚点失败: {}", e.getMessage());
        }
        return anchors;
    }

    private String buildChapterOutline(List<ChapterAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return "（未识别到结构化章节，请将 chapterIndex 置为 -1，chapterTitle 置为空字符串）";
        }
        StringBuilder sb = new StringBuilder();
        for (ChapterAnchor anchor : anchors) {
            sb.append("  - chapterIndex=")
                    .append(anchor.index())
                    .append(", chapterTitle=\"")
                    .append(JsonUtils.escapeJsonString(anchor.title()))
                    .append("\"\n");
        }
        return sb.toString();
    }

    private JsonNode enrichIssuesWithChapterAnchors(JsonNode parsed, List<ChapterAnchor> anchors) {
        if (parsed == null || !parsed.isObject() || anchors == null || anchors.isEmpty()) return parsed;
        ObjectNode root = parsed.deepCopy();
        JsonNode issues = root.path("issues");
        if (!issues.isArray()) return root;
        for (JsonNode issue : issues) {
            if (!issue.isObject()) continue;
            ObjectNode item = (ObjectNode) issue;
            int idx = item.path("chapterIndex").isInt() ? item.path("chapterIndex").asInt(-1) : -1;
            if (idx < 0 || idx >= anchors.size()) {
                String searchText = item.path("chapterTitle").asText("") + "\n"
                        + item.path("location").asText("") + "\n"
                        + item.path("description").asText("");
                idx = matchChapterAnchor(searchText, anchors);
            }
            if (idx >= 0 && idx < anchors.size()) {
                ChapterAnchor anchor = anchors.get(idx);
                item.put("chapterIndex", anchor.index());
                item.put("chapterTitle", anchor.title());
            } else {
                item.put("chapterIndex", -1);
                if (!item.has("chapterTitle")) item.put("chapterTitle", "");
            }
        }
        return root;
    }

    private int matchChapterAnchor(String text, List<ChapterAnchor> anchors) {
        String normalized = normalizeAnchorText(text);
        if (normalized.isBlank()) return -1;
        int best = -1;
        int bestScore = 0;
        for (ChapterAnchor anchor : anchors) {
            String title = anchor.normalizedTitle();
            if (!title.isBlank() && (normalized.contains(title) || title.contains(normalized))) {
                int score = Math.min(normalized.length(), title.length()) + 10;
                if (score > bestScore) {
                    bestScore = score;
                    best = anchor.index();
                }
                continue;
            }
            if (!anchor.number().isBlank() && normalized.contains(anchor.number().replace(".", ""))) {
                int score = anchor.number().length() + 1;
                if (score > bestScore) {
                    bestScore = score;
                    best = anchor.index();
                }
            }
        }
        return best;
    }

    private String normalizeAnchorText(String value) {
        if (value == null) return "";
        return value.toLowerCase()
                .replaceAll("[\\s#*_`~\\-—–.,，。、:：;；!！?？()（）\\[\\]【】{}<>《》\"“”'‘’|/\\\\]+", "");
    }

    /** 从报告 issues JSON 中按 indexes / severities 筛选 */
    private List<JsonNode> selectIssues(String issuesRaw, PrdReviewFixRequest request) {
        List<JsonNode> all = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(issuesRaw);
            JsonNode arr = root.path("issues");
            if (!arr.isArray()) {
                // 兼容 issues 直接是数组
                if (root.isArray()) arr = root;
                else return all;
            }
            for (JsonNode n : arr) all.add(n);
        } catch (Exception e) {
            log.warn("解析审查 issues 失败", e);
            return all;
        }

        List<JsonNode> selected = new ArrayList<>();
        if (request != null && request.getIssueIndexes() != null && !request.getIssueIndexes().isEmpty()) {
            for (Integer idx : request.getIssueIndexes()) {
                if (idx != null && idx >= 0 && idx < all.size()) selected.add(all.get(idx));
            }
            return selected;
        }

        Set<String> sevs;
        if (request != null && request.getSeverities() != null && !request.getSeverities().isEmpty()) {
            sevs = request.getSeverities().stream().map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
        } else {
            sevs = DEFAULT_FIX_SEVERITIES;
        }
        for (JsonNode n : all) {
            String sev = n.path("severity").asText("").toUpperCase();
            if (sevs.contains(sev)) selected.add(n);
        }
        return selected;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化任务参数", e);
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
