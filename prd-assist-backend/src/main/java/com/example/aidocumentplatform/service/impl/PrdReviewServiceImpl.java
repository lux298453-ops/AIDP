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
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.model.enums.TemplateType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.service.TaskService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TaskService taskService;
    private final PrdContentParser prdContentParser;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ======================== 提交审查 ========================

    @Override
    public Long submit(PrdReviewRequest request, Long userId) {
        // 解析/落库 PRD，保证始终有有效 prdDocumentId
        ResolvedPrd resolved = resolveOrCreatePrd(request, userId);

        List<String> dims = request.getDimensions() != null ? request.getDimensions() : List.of();
        String dimsJson = toJson(dims);
        String inputParams = buildReviewTaskInput(resolved.prdDocId(), dims,
                request.getRequirement(), truncate(resolved.reviewText(), 4000));

        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PRD_REVIEW, inputParams);
        AsyncTask task = reservation.task();
        log.info("PRD审查任务已创建: taskId={}, userId={}, prdDocId={}, dimensions={}",
                task.getId(), userId, resolved.prdDocId(), dims);

        if (reservation.created()) {
            applicationContext.getBean(PrdReviewServiceImpl.class)
                    .execute(task.getId(), resolved.reviewText(), dims, request.getRequirement(),
                            userId, resolved.prdDocId(), resolved.contentJson());
        }
        return task.getId();
    }

    private String buildReviewTaskInput(Long prdDocumentId, List<String> dimensions,
                                        String requirement, String prdContent) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("prdDocumentId", prdDocumentId);
            ArrayNode dimensionArray = root.putArray("dimensions");
            if (dimensions != null) {
                for (String dimension : dimensions) {
                    dimensionArray.add(dimension);
                }
            }
            root.put("requirement", requirement == null ? "" : requirement);
            root.put("prdContent", prdContent == null ? "" : prdContent);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建 PRD 审查任务参数失败", e);
        }
    }
    @Async("asyncTaskExecutor")
    public void execute(Long taskId, String prdContent, List<String> dimensions, String requirement,
                        Long userId, Long prdDocId, String contentJson) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) return;
        try {
            asyncTaskLifecycleService.markRunning(taskId);
            taskService.pushProgress(taskId, 10, "AI 正在审查 PRD...");

            List<ChapterAnchor> chapterAnchors = extractChapterAnchors(contentJson);
            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    prdContent, dimensions, requirement, buildChapterOutline(chapterAnchors));
            String aiResponse;
            AiRequestContext.setUserId(userId);
            try {
                aiResponse = aiClient.generateStream(systemPrompt, userPrompt,
                        delta -> taskService.pushContentDelta(taskId, delta));
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
            report = asyncTaskLifecycleService.saveReviewReportAndMarkSuccess(taskId, report);
            taskService.pushProgress(taskId, 100, "审查完成");
            log.info("PRD审查成功: taskId={}, reportId={}, prdDocId={}", taskId, report.getId(), prdDocId);
        } catch (Exception e) {
            log.error("PRD审查失败: taskId={}", taskId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
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

        Long sourcePrdId = request != null && request.getSourcePrdDocumentId() != null
                ? request.getSourcePrdDocumentId()
                : report.getPrdDocumentId();
        PrdDocument source = prdDocumentRepository.findById(sourcePrdId)
                .orElseThrow(() -> new IllegalArgumentException("关联的 PRD 不存在"));
        if (!source.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该 PRD");
        }

        List<JsonNode> selectedIssues = selectIssues(report.getIssues(), request);
        if (selectedIssues.isEmpty()) {
            throw new IllegalArgumentException("没有可修复的问题，请勾选问题或选择严重程度");
        }

        String issuesJson = toJson(selectedIssues);
        boolean localChapterFix = canUseLocalChapterFix(source.getContent(), selectedIssues);
        String inputParams = "{\"reportId\":" + reportId
                + ",\"sourcePrdId\":" + source.getId()
                + ",\"issueCount\":" + selectedIssues.size()
                + ",\"fixMode\":\"" + (localChapterFix ? "LOCAL_CHAPTER" : "FULL_DOCUMENT") + "\"}";

        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PRD_REVIEW_FIX, inputParams);
        AsyncTask task = reservation.task();
        log.info("PRD审查修复任务已创建: taskId={}, reportId={}, issues={}, mode={}",
                task.getId(), reportId, selectedIssues.size(), localChapterFix ? "LOCAL_CHAPTER" : "FULL_DOCUMENT");

        if (reservation.created()) {
            applicationContext.getBean(PrdReviewServiceImpl.class)
                    .executeFix(task.getId(), source, issuesJson, userId, localChapterFix);
        }
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void executeFix(Long taskId, PrdDocument source, String issuesJson, Long userId, boolean localChapterFix) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) return;
        try {
            asyncTaskLifecycleService.markRunning(taskId);
            taskService.pushProgress(taskId, 10,
                    localChapterFix ? "AI 正在修复该问题对应章节..." : "AI 正在根据审查意见修订 PRD...");

            String jsonContent;
            if (localChapterFix) {
                try {
                    jsonContent = executeLocalChapterFix(taskId, source, issuesJson, userId);
                } catch (Exception localError) {
                    log.warn("章节级修复失败，回退为整篇修复: taskId={}, reason={}",
                            taskId, localError.getMessage());
                    taskService.pushProgress(taskId, 35, "章节级修复失败，正在回退为整篇修复...");
                    jsonContent = executeFullDocumentFix(taskId, source, issuesJson, userId);
                    localChapterFix = false;
                }
            } else {
                jsonContent = executeFullDocumentFix(taskId, source, issuesJson, userId);
            }

            PrdDocument fixed = buildFixedPrdDocument(source, taskId, userId, jsonContent);
            fixed = asyncTaskLifecycleService.savePrdDocumentAndMarkSuccess(taskId, fixed);
            taskService.pushProgress(taskId, 100, localChapterFix ? "该问题对应章节修复完成" : "修订完成");
            log.info("PRD审查修复成功: taskId={}, newPrdId={}, sourcePrdId={}, mode={}",
                    taskId, fixed.getId(), source.getId(), localChapterFix ? "LOCAL_CHAPTER" : "FULL_DOCUMENT");
        } catch (Exception e) {
            log.error("PRD审查修复失败: taskId={}", taskId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
            taskService.pushProgress(taskId, 0, "修复失败: " + e.getMessage());
        }
    }

    private String executeFullDocumentFix(Long taskId, PrdDocument source, String issuesJson, Long userId) {
        String systemPrompt = fixPromptTemplate.getSystemPrompt();
        String userPrompt = fixPromptTemplate.buildUserPrompt(source.getContent(), issuesJson);
        String aiResponse;
        AiRequestContext.setUserId(userId);
        try {
            aiResponse = aiClient.generateStream(systemPrompt, userPrompt,
                    delta -> taskService.pushContentDelta(taskId, delta));
        } finally {
            AiRequestContext.clear();
        }
        return prdContentParser.normalizeToJson(aiResponse);
    }

    private boolean canUseLocalChapterFix(String prdContent, List<JsonNode> selectedIssues) {
        if (selectedIssues == null || selectedIssues.size() != 1) return false;
        int chapterIndex = selectedIssues.get(0).path("chapterIndex").asInt(-1);
        if (chapterIndex < 0) return false;
        try {
            JsonNode root = prdContentParser.normalize(prdContent);
            JsonNode chapters = root.path("chapters");
            return chapters.isArray()
                    && chapterIndex < chapters.size()
                    && chapters.get(chapterIndex).isObject();
        } catch (Exception e) {
            log.warn("判断章节级修复失败，将使用整篇修复: {}", e.getMessage());
            return false;
        }
    }

    private String executeLocalChapterFix(Long taskId, PrdDocument source, String issuesJson, Long userId) throws Exception {
        JsonNode issue = firstIssue(issuesJson);
        JsonNode prdRoot = prdContentParser.normalize(source.getContent());
        JsonNode chapters = prdRoot.path("chapters");
        int chapterIndex = issue.path("chapterIndex").asInt(-1);
        if (!chapters.isArray() || chapterIndex < 0 || chapterIndex >= chapters.size()) {
            throw new IllegalArgumentException("问题未能定位到有效章节");
        }

        JsonNode target = chapters.get(chapterIndex);
        String chapterTitle = target.path("title").asText("未命名章节");
        String chapterContent = target.path("content").asText("");
        String prompt = fixPromptTemplate.buildSingleChapterPatchPrompt(
                prdRoot.path("title").asText(source.getTitle()),
                prdRoot.path("summary").asText(source.getDescription()),
                buildChapterOutline(extractChapterAnchors(prdRoot.toString())),
                chapterIndex,
                chapterTitle,
                chapterContent,
                issue.toString());

        taskService.pushProgress(taskId, 20, "AI 正在局部修复章节：" + chapterTitle);
        String aiResponse;
        AiRequestContext.setUserId(userId);
        try {
            aiResponse = aiClient.generateStream(fixPromptTemplate.getSystemPrompt(), prompt,
                    delta -> taskService.pushContentDelta(taskId, delta));
        } finally {
            AiRequestContext.clear();
        }

        JsonNode patch = prdContentParser.parseObject(aiResponse);
        String patchedContent = extractPatchedChapterContent(patch, chapterIndex);
        if (patchedContent.isBlank()) {
            throw new IllegalArgumentException("AI 未返回有效章节内容");
        }

        ObjectNode merged = prdRoot.deepCopy();
        JsonNode mergedChapters = merged.path("chapters");
        if (!mergedChapters.isArray() || chapterIndex >= mergedChapters.size()) {
            throw new IllegalArgumentException("PRD 章节结构异常，无法合并局部修复");
        }
        if (!mergedChapters.get(chapterIndex).isObject()) {
            throw new IllegalArgumentException("目标章节不是对象结构");
        }
        ObjectNode targetChapter = ((ObjectNode) mergedChapters.get(chapterIndex));
        targetChapter.put("content", patchedContent);
        if (targetChapter.path("title").asText("").isBlank()) {
            targetChapter.put("title", chapterTitle);
        }
        if (patch.hasNonNull("changeSummary")) {
            taskService.pushProgress(taskId, 70, "已完成局部修复：" + truncate(patch.path("changeSummary").asText(), 80));
        }
        return merged.toString();
    }

    private JsonNode firstIssue(String issuesJson) throws Exception {
        JsonNode root = objectMapper.readTree(issuesJson == null || issuesJson.isBlank() ? "[]" : issuesJson);
        if (root.isArray() && root.size() > 0) return root.get(0);
        if (root.isObject()) return root;
        throw new IllegalArgumentException("待修复问题为空");
    }

    private String extractPatchedChapterContent(JsonNode patch, int chapterIndex) {
        if (patch == null || patch.isMissingNode() || patch.isNull()) return "";
        if (patch.hasNonNull("content")) return patch.path("content").asText("");
        JsonNode chapters = patch.path("chapters");
        if (chapters.isArray()) {
            if (chapterIndex >= 0 && chapterIndex < chapters.size()) {
                String content = chapters.get(chapterIndex).path("content").asText("");
                if (!content.isBlank()) return content;
            }
            for (JsonNode chapter : chapters) {
                String content = chapter.path("content").asText("");
                if (!content.isBlank()) return content;
            }
        }
        return "";
    }

    private PrdDocument buildFixedPrdDocument(PrdDocument source, Long taskId, Long userId, String jsonContent) {
        String newTitle = source.getTitle();
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            String t = root.path("title").asText("");
            if (!t.isBlank()) {
                newTitle = t.length() > 50 ? t.substring(0, 50) : t;
            }
        } catch (Exception ignored) { /* keep original title */ }

        String titleWithVersion = buildRevisionTitle(newTitle);
        return PrdDocument.builder()
                .userId(userId)
                .taskId(taskId)
                .title(titleWithVersion)
                .description(source.getDescription())
                .content(jsonContent)
                .sourceType(source.getSourceType() != null ? source.getSourceType() : DocumentSourceType.MANUAL)
                .template(source.getTemplate() != null ? source.getTemplate() : TemplateType.STANDARD)
                .detailLevel(source.getDetailLevel() != null ? source.getDetailLevel() : DetailLevel.DETAILED)
                .build();
    }

    private String buildRevisionTitle(String title) {
        String safeTitle = title == null || title.isBlank() ? "PRD 文档" : title;
        if (safeTitle.contains("审查修订")) return safeTitle.length() > 50 ? safeTitle.substring(0, 50) : safeTitle;
        String versionSuffix = "（审查修订 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "）";
        String base = safeTitle.length() > 30 ? safeTitle.substring(0, 30) : safeTitle;
        String titleWithVersion = base + versionSuffix;
        return titleWithVersion.length() > 50 ? titleWithVersion.substring(0, 50) : titleWithVersion;
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

    private List<JsonNode> parseIssuesArray(String issuesRaw) {
        List<JsonNode> all = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(issuesRaw);
            JsonNode arr = root.path("issues");
            if (!arr.isArray()) {
                if (root.isArray()) arr = root;
                else return all;
            }
            for (JsonNode n : arr) all.add(n);
        } catch (Exception e) {
            log.warn("解析审查 issues 失败", e);
        }
        return all;
    }

    // ======================== 内联精准修复 ========================

    @Override
    public Map<String, Object> submitInlineFix(Long reportId, int issueIndex, Long sourcePrdDocumentId, Long userId) {
        ReviewReport report = reviewReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("审查报告不存在"));
        if (!report.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该审查报告");
        }

        Long sourcePrdId = sourcePrdDocumentId != null ? sourcePrdDocumentId : report.getPrdDocumentId();
        if (sourcePrdId == null || sourcePrdId <= 0) {
            throw new IllegalArgumentException("该报告未关联 PRD 文档，无法修复");
        }
        PrdDocument source = prdDocumentRepository.findById(sourcePrdId)
                .orElseThrow(() -> new IllegalArgumentException("关联的 PRD 不存在"));
        if (!source.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该 PRD");
        }

        List<JsonNode> allIssues = parseIssuesArray(report.getIssues());
        if (issueIndex < 0 || issueIndex >= allIssues.size()) {
            throw new IllegalArgumentException("问题索引无效: " + issueIndex);
        }
        JsonNode issue = allIssues.get(issueIndex);

        int chapterIndex = issue.path("chapterIndex").asInt(-1);
        String chapterTitle = issue.path("chapterTitle").asText("");
        String chapterContent = "";
        if (chapterIndex >= 0) {
            try {
                JsonNode prdRoot = prdContentParser.normalize(source.getContent());
                JsonNode chapters = prdRoot.path("chapters");
                if (chapters.isArray() && chapterIndex < chapters.size()) {
                    chapterContent = chapters.get(chapterIndex).path("content").asText("");
                    if (chapterTitle.isBlank()) {
                        chapterTitle = chapters.get(chapterIndex).path("title").asText("");
                    }
                }
            } catch (Exception e) {
                log.warn("内联修复：无法解析章节内容，将使用空内容: {}", e.getMessage());
            }
        }

        String prompt = fixPromptTemplate.buildInlineFixPrompt(chapterTitle, chapterContent, issue.toString());
        String aiResponse;
        AiRequestContext.setUserId(userId);
        try {
            aiResponse = aiClient.generate(fixPromptTemplate.getSystemPrompt(), prompt);
        } finally {
            AiRequestContext.clear();
        }

        JsonNode patch;
        try {
            patch = prdContentParser.parseObject(aiResponse);
        } catch (Exception e) {
            log.error("内联修复 AI 返回解析失败: {}", aiResponse);
            throw new IllegalArgumentException("AI 返回格式异常，请重试");
        }

        String oldText = patch.path("oldText").asText("");
        String newText = patch.path("newText").asText("");
        String changeSummary = patch.path("changeSummary").asText("已修复");

        if (oldText.isBlank()) {
            throw new IllegalArgumentException("AI 未能定位到需要修改的原文片段，请尝试整章修复");
        }

        // 在章节内容中定位 oldText 并替换
        String patchedContent;
        if (chapterContent.contains(oldText)) {
            patchedContent = chapterContent.replace(oldText, newText);
        } else {
            // 模糊匹配：尝试找最相似的子串
            String bestMatch = findBestMatch(chapterContent, oldText);
            if (bestMatch != null) {
                patchedContent = chapterContent.replace(bestMatch, newText);
                oldText = bestMatch; // 更新为实际匹配到的文本
            } else {
                // 找不到匹配，追加到章节末尾
                patchedContent = chapterContent + "\n\n" + newText;
                oldText = "";
            }
        }

        // 合并到完整 PRD
        String fullContent;
        try {
            JsonNode prdRoot = prdContentParser.normalize(source.getContent());
            ObjectNode merged = prdRoot.deepCopy();
            JsonNode chapters = merged.path("chapters");
            if (chapters.isArray() && chapterIndex >= 0 && chapterIndex < chapters.size()) {
                ((ObjectNode) chapters.get(chapterIndex)).put("content", patchedContent);
            }
            fullContent = merged.toString();
        } catch (Exception e) {
            fullContent = source.getContent();
        }

        // 保存为新版本
        PrdDocument fixed = buildFixedPrdDocument(source, null, userId, fullContent);
        fixed = prdDocumentRepository.save(fixed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prdDocumentId", fixed.getId());
        result.put("chapterIndex", chapterIndex);
        result.put("oldText", oldText);
        result.put("newText", newText);
        result.put("changeSummary", changeSummary);
        result.put("patchedContent", patchedContent);
        return result;
    }

    private String findBestMatch(String content, String target) {
        if (content == null || target == null || target.length() < 5) return null;
        // 尝试找 target 的前 30 个字符
        String prefix = target.length() > 30 ? target.substring(0, 30) : target;
        if (content.contains(prefix)) return prefix;
        // 尝试找 target 的后 30 个字符
        String suffix = target.length() > 30 ? target.substring(target.length() - 30) : target;
        if (content.contains(suffix)) return suffix;
        // 尝试找 target 中最长的连续子串
        for (int len = Math.min(target.length(), 20); len >= 8; len--) {
            for (int i = 0; i + len <= target.length(); i++) {
                String sub = target.substring(i, i + len);
                if (content.contains(sub)) return sub;
            }
        }
        return null;
    }
}
