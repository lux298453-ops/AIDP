package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.common.FileStorage;
import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.AssetMode;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
import com.example.aidocumentplatform.service.PrototypeImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PrototypeGenerateServiceImpl implements PrototypeGenerateService {

    private static final String PRIMARY_ASSET_PLACEHOLDER = "__PROTOTYPE_PRIMARY_ASSET__";

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final AiClient aiClient;
    private final PrototypePromptTemplate promptTemplate;
    private final TaskService taskService;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final PrototypeImageService prototypeImageService;
    private final FileStorage fileStorage;
    private final PrototypeQualityGuard prototypeQualityGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrototypeGenerateServiceImpl(
            AsyncTaskRepository asyncTaskRepository,
            PrototypeResultRepository prototypeResultRepository,
            AiClient aiClient,
            PrototypePromptTemplate promptTemplate,
            TaskService taskService,
            ApplicationContext applicationContext,
            IdempotentTaskService idempotentTaskService,
            AsyncTaskLifecycleService asyncTaskLifecycleService,
            PrototypeImageService prototypeImageService,
            FileStorage fileStorage,
            PrototypeQualityGuard prototypeQualityGuard) {
        this.asyncTaskRepository = asyncTaskRepository;
        this.prototypeResultRepository = prototypeResultRepository;
        this.aiClient = aiClient;
        this.promptTemplate = promptTemplate;
        this.taskService = taskService;
        this.applicationContext = applicationContext;
        this.idempotentTaskService = idempotentTaskService;
        this.asyncTaskLifecycleService = asyncTaskLifecycleService;
        this.prototypeImageService = prototypeImageService;
        this.fileStorage = fileStorage;
        this.prototypeQualityGuard = prototypeQualityGuard;
    }

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
            root.put("assetMode", request.getAssetMode() == null ? AssetMode.AUTO.name() : request.getAssetMode().name());
            root.put("description", request.getDescription());
            root.put("hasReferenceImage", hasRef);
            if (request.getClarificationAnswers() != null && !request.getClarificationAnswers().isEmpty()) {
                root.putPOJO("clarificationAnswers", request.getClarificationAnswers());
            }
            if (request.getGenerationBrief() != null && !request.getGenerationBrief().isBlank()) {
                root.put("generationBrief", request.getGenerationBrief());
            }
            if (request.getAssetPlan() != null) {
                root.putPOJO("assetPlan", request.getAssetPlan());
            }
            if (request.getAssetPlans() != null && !request.getAssetPlans().isEmpty()) {
                root.putPOJO("assetPlans", request.getAssetPlans());
            }
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

            List<PrototypeAssetPlan> effectivePlans = effectiveAssetPlans(request);
            String effectiveBrief = appendAssetInstruction(request.getGenerationBrief(), effectivePlans);

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    request.getDescription(),
                    request.getPlatform(),
                    request.getPrototypeType(),
                    hasRef,
                     request.getReferenceImageFileName(),
                     request.getAssetMode(),
                     request.getClarificationAnswers(),
                     effectiveBrief);

            String aiOutput;
            AiRequestContext.setUserId(userId);
            try {
                aiOutput = generateHtmlWithConcurrencyRetry(
                        taskId, request, hasRef, systemPrompt, userPrompt);
            } finally {
                AiRequestContext.clear();
            }

            List<ResolvedAsset> resolvedAssets = generateAssetsSequentially(
                    request, userId, taskId, effectivePlans);

            // 先清洗/落库再推 SSE，避免进度推送异常影响保存
            String content = request.getPrototypeType() == PrototypeType.MULTI_PAGE
                    ? cleanJson(aiOutput, request.getPlatform())
                    : cleanHtml(aiOutput, request.getPlatform());
            PrototypeQualityGuard.Result guarded = prototypeQualityGuard.inspectAndRepairAssets(
                    content,
                    request.getPlatform(),
                    effectiveBrief,
                    resolvedAssets.stream()
                            .map(asset -> new PrototypeQualityGuard.Asset(
                                    asset.key(), asset.targetPage(), asset.dataUrl()))
                            .toList());
            content = guarded.html();
            if (!guarded.findings().isEmpty()) {
                log.info("原型质量守卫已处理: taskId={}, findings={}", taskId, guarded.findings());
            }

            PrototypeResult proto = PrototypeResult.builder()
                    .userId(userId).taskId(taskId).prdDocumentId(request.getPrdDocumentId())
                    .prototypeType(request.getPrototypeType()).platform(request.getPlatform())
                    .content(content)
                    .referenceImageUrl(resolvedAssets.isEmpty() ? null : resolvedAssets.get(0).storedPath())
                    .build();
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

    private List<PrototypeAssetPlan> effectiveAssetPlans(PrototypeGenerateRequest request) {
        if (request.getPrototypeType() == PrototypeType.MULTI_PAGE
                && request.getAssetPlans() != null && !request.getAssetPlans().isEmpty()) {
            return request.getAssetPlans().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(PrototypeAssetPlan::isRequired)
                    .limit(3)
                    .toList();
        }
        PrototypeAssetPlan primary = request.getAssetPlan();
        return primary != null && primary.isRequired() ? List.of(primary) : List.of();
    }

    private List<ResolvedAsset> generateAssetsSequentially(
            PrototypeGenerateRequest request,
            Long userId,
            Long taskId,
            List<PrototypeAssetPlan> plans) {
        List<ResolvedAsset> assets = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            ResolvedAsset asset = resolveAsset(request, plans.get(i), i, plans.size(), userId, taskId);
            if (asset != null) assets.add(asset);
        }
        return List.copyOf(assets);
    }

    private ResolvedAsset resolveAsset(
            PrototypeGenerateRequest request,
            PrototypeAssetPlan plan,
            int index,
            int total,
            Long userId,
            Long taskId) {
        if (plan == null || !plan.isRequired()) return null;
        String key = assetKey(plan, index);

        try {
            if ("REFERENCE".equalsIgnoreCase(plan.getSource())
                    && request.getReferenceImageBase64() != null
                    && !request.getReferenceImageBase64().isBlank()) {
                return new ResolvedAsset(
                        key,
                        plan.getTargetPage(),
                        toDataUrl(request.getReferenceImageMimeType(), request.getReferenceImageBase64()),
                        request.getReferenceImagePath(),
                        plan.getRole());
            }
            if (!"GENERATED".equalsIgnoreCase(plan.getSource())) return null;

            taskService.pushProgress(taskId, 22,
                    total > 1 ? "正在生成第 " + (index + 1) + "/" + total + " 张页面素材..."
                            : "正在生成页面所需的核心视觉素材...");
            PrototypeImageService.GeneratedImage image = generateImageWithConcurrencyRetry(
                    enrichAssetPlan(plan, request, userId), userId, taskId, key);
            String path = fileStorage.store(image.bytes(), "prototype-" + key + image.fileExtension());
            String dataUrl = "data:" + image.mimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.bytes());
            taskService.pushProgress(taskId, 30, "核心视觉素材已生成，正在组装原型页面...");
            return new ResolvedAsset(key, plan.getTargetPage(), dataUrl, path, plan.getRole());
        } catch (Exception e) {
            log.warn("原型页面素材生成失败，跳过当前素材: taskId={}, key={}, targetPage={}, reason={}",
                    taskId, key, plan.getTargetPage(), rootMessage(e));
            taskService.pushProgress(taskId, 25, "部分图片素材暂不可用，继续组装其他页面...");
            return null;
        }
    }

    private PrototypeImageService.GeneratedImage generateImageWithConcurrencyRetry(
            PrototypeAssetPlan plan, Long userId, Long taskId, String key) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return prototypeImageService.generate(plan, userId);
            } catch (RuntimeException e) {
                boolean retryable = isConcurrencyLimit(e) || isTransientTransportError(e);
                if (!retryable || attempt == maxAttempts) throw e;
                long delayMillis = attempt * 3000L;
                log.warn("图片接口暂时不可用，等待后重试: taskId={}, key={}, attempt={}/{}, delayMs={}, reason={}",
                        taskId, key, attempt + 1, maxAttempts, delayMillis, rootMessage(e));
                taskService.pushProgress(taskId, 24, "图片服务繁忙，正在等待重试...");
                sleepBeforeRetry(delayMillis);
            }
        }
        throw new IllegalStateException("图片生成重试状态异常");
    }

    /** 中转站连接抖动（连接中断、超时、SSL、上游故障）属于瞬时错误，值得重试 */
    private boolean isTransientTransportError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String className = current.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            String message = current.getMessage() == null
                    ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (className.contains("prematureclose")
                    || className.contains("closedchannel")
                    || className.contains("timeout")
                    || className.contains("ssl")
                    || message.contains("prematurely closed")
                    || message.contains("connection reset")
                    || message.contains("timed out")
                    || message.contains("upstream request failed")
                    || message.contains("stream_read_error")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private String generateHtmlWithConcurrencyRetry(
            Long taskId,
            PrototypeGenerateRequest request,
            boolean hasRef,
            String systemPrompt,
            String userPrompt) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (hasRef) {
                    return aiClient.generateWithImageStream(
                            systemPrompt,
                            userPrompt,
                            request.getReferenceImageMimeType(),
                            request.getReferenceImageBase64(),
                            delta -> taskService.pushContentDelta(taskId, delta));
                }
                return aiClient.generateStream(
                        systemPrompt,
                        userPrompt,
                        delta -> taskService.pushContentDelta(taskId, delta));
            } catch (RuntimeException e) {
                if (!isConcurrencyLimit(e) || attempt == maxAttempts) throw e;
                long delayMillis = attempt * 5000L;
                log.warn("HTML 生成遇到中转站并发限制，等待后重试: taskId={}, attempt={}/{}, delayMs={}",
                        taskId, attempt + 1, maxAttempts, delayMillis);
                taskService.pushProgress(taskId, 16, "中转站繁忙，正在等待后重试原型生成...");
                sleepBeforeRetry(delayMillis);
            }
        }
        throw new IllegalStateException("HTML 生成重试状态异常");
    }

    private boolean isConcurrencyLimit(Throwable error) {
        String message = rootMessage(error).toLowerCase(java.util.Locale.ROOT);
        return message.contains("concurrency limit")
                || message.contains("too many concurrent")
                || message.contains("并发") && message.contains("限制");
    }

    private void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图片生成重试被中断", e);
        }
    }

    private PrototypeAssetPlan enrichAssetPlan(
            PrototypeAssetPlan plan, PrototypeGenerateRequest request, Long userId) {
        StringBuilder prompt = new StringBuilder(plan.getPrompt() == null ? "" : plan.getPrompt().trim());
        if (plan.getTargetPage() != null && !plan.getTargetPage().isBlank()) {
            // 多页面每页独立素材：明确本图只属于当前页，防止模型把简报里的其他页面画成三联拼图
            prompt.append("\nThis image belongs ONLY to the page \"")
                    .append(plan.getTargetPage().trim())
                    .append("\". Other pages have their own separate images. Depict one single composition for this page only.");
        }
        if (request.getGenerationBrief() != null && !request.getGenerationBrief().isBlank()) {
            prompt.append("\nOverall confirmed composition and visual direction (style reference only, do not merge other pages into this image): ")
                    .append(request.getGenerationBrief().trim());
        }
        if (request.getClarificationAnswers() != null) {
            for (String answer : request.getClarificationAnswers()) {
                if (answer != null && !answer.isBlank()) {
                    prompt.append("\nConfirmed visual requirement: ").append(answer.trim());
                }
            }
        }
        String referenceStyle = extractReferenceStyle(request, userId);
        if (!referenceStyle.isBlank()) {
            prompt.append("\nMatch these visual traits from the uploaded style reference: ")
                    .append(referenceStyle);
        }
        return PrototypeAssetPlan.builder()
                .key(plan.getKey())
                .targetPage(plan.getTargetPage())
                .required(plan.isRequired())
                .source(plan.getSource())
                .role(plan.getRole())
                .prompt(prompt.toString())
                .aspectRatio(plan.getAspectRatio())
                .transparentBackground(plan.isTransparentBackground())
                .build();
    }

    private String extractReferenceStyle(PrototypeGenerateRequest request, Long userId) {
        if (request.getReferenceImageBase64() == null || request.getReferenceImageBase64().isBlank()) return "";
        try {
            AiRequestContext.setUserId(userId);
            return aiClient.generateWithImage(
                    "You extract visual style from a reference image for another image model. Return concise plain text only.",
                    "Describe only reusable visual traits: art direction, palette, lighting, material, rendering style, line quality and atmosphere. Do not identify people and do not describe UI layout or text.",
                    request.getReferenceImageMimeType(),
                    request.getReferenceImageBase64()).trim();
        } catch (Exception e) {
            log.warn("参考图风格提取失败，核心素材继续按文字需求生成: {}", rootMessage(e));
            return "";
        } finally {
            AiRequestContext.clear();
        }
    }

    private String appendAssetInstruction(String generationBrief, List<PrototypeAssetPlan> plans) {
        String base = generationBrief == null ? "" : generationBrief.trim();
        if (plans == null || plans.isEmpty()) {
            return base + "\n素材约束：当前没有可嵌入的真实图片地址，不得编造网络图片 URL；用稳定布局和克制的占位区域完成页面。";
        }
        StringBuilder instruction = new StringBuilder(base)
                .append("\n系统正在并行准备以下真实视觉素材。必须使用对应占位符，禁止改写、转义或替换 URL：");
        for (int i = 0; i < plans.size(); i++) {
            PrototypeAssetPlan plan = plans.get(i);
            instruction.append("\n- ")
                    .append(plan.getTargetPage() == null || plan.getTargetPage().isBlank() ? "相关页面" : plan.getTargetPage())
                    .append("：<img src=\"")
                    .append(assetPlaceholder(plan, i))
                    .append("\" alt=\"")
                    .append(plan.getRole() == null ? "页面视觉素材" : plan.getRole())
                    .append("\">");
        }
        if (plans.size() == 1) {
            instruction.append("\n兼容占位符 ").append(PRIMARY_ASSET_PLACEHOLDER).append(" 也可使用。");
        }
        return instruction.append("\n每个占位符只放在其目标页面。图片是页面主体或背景素材，不是额外说明卡片；按简报安排裁切、层级和光效。").toString();
    }

    private String assetPlaceholder(PrototypeAssetPlan plan, int index) {
        return "__PROTOTYPE_ASSET_" + assetKey(plan, index) + "__";
    }

    private String assetKey(PrototypeAssetPlan plan, int index) {
        String value = plan == null || plan.getKey() == null ? "" : plan.getKey();
        String key = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.isBlank() ? "asset_" + (index + 1) : key;
    }

    private String toDataUrl(String mimeType, String base64) {
        String mime = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        return "data:" + mime + ";base64," + base64;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ResolvedAsset(
            String key, String targetPage, String dataUrl, String storedPath, String role) {
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
