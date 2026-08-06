package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrdGeneratePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrdGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.enums.DocumentSourceType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.service.PrdGenerateService;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.util.PrdContentParser;
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
public class PrdGenerateServiceImpl implements PrdGenerateService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final AiClient aiClient;
    private final PrdGeneratePromptTemplate promptTemplate;
    private final TaskService taskService;
    private final PrdContentParser prdContentParser;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    public Long submit(PrdGenerateRequest request, Long userId) {
        String inputParams = buildInputJson(request);
        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PRD_GENERATE, inputParams);
        AsyncTask task = reservation.task();

        log.info("PRD generation task prepared: taskId={}, userId={}, featureName={}",
                task.getId(), userId, request.getFeatureName());
        if (reservation.created()) {
            applicationContext.getBean(PrdGenerateServiceImpl.class).execute(task.getId(), request, userId);
        }
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, PrdGenerateRequest request, Long userId) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) {
            return;
        }

        try {
            asyncTaskLifecycleService.markRunning(taskId);
            taskService.pushProgress(taskId, 10, "AI 正在生成 PRD...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    request.getFeatureName(),
                    request.getDescription(),
                    request.getDetailLevel(),
                    request.getCustomTemplateContent());
            log.info("PRD generation AI call started: taskId={}, template={}, promptLen={}",
                    taskId, request.getTemplate(), userPrompt.length());

            String aiResponse;
            AiRequestContext.setUserId(userId);
            try {
                aiResponse = aiClient.generateStream(systemPrompt, userPrompt,
                        delta -> taskService.pushContentDelta(taskId, delta));
            } finally {
                AiRequestContext.clear();
            }

            String jsonContent = prdContentParser.normalizeToJson(aiResponse);
            String safeDescription = request.getDescription() != null && request.getDescription().length() > 50000
                    ? request.getDescription().substring(0, 49997) + "..."
                    : request.getDescription();

            PrdDocument doc = PrdDocument.builder()
                    .userId(userId)
                    .taskId(taskId)
                    .title(request.getFeatureName())
                    .description(safeDescription)
                    .content(jsonContent)
                    .sourceType(DocumentSourceType.MANUAL)
                    .template(request.getTemplate())
                    .detailLevel(request.getDetailLevel())
                    .build();
            doc = asyncTaskLifecycleService.savePrdDocumentAndMarkSuccess(taskId, doc);

            taskService.pushProgress(taskId, 100, "PRD 生成完成");
            log.info("PRD generation finished: taskId={}, documentId={}", taskId, doc.getId());
        } catch (Exception e) {
            log.error("PRD generation failed: taskId={}", taskId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
            taskService.pushProgress(taskId, 0, "生成失败: " + e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private String buildInputJson(PrdGenerateRequest req) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("featureName", req.getFeatureName());
            root.put("description", req.getDescription());
            root.put("template", req.getTemplate().name());
            root.put("detailLevel", req.getDetailLevel().name());
            if (req.getCustomTemplateFileName() != null) {
                root.put("customTemplateFileName", req.getCustomTemplateFileName());
            }
            if (req.getCustomTemplateContent() != null && !req.getCustomTemplateContent().isBlank()) {
                String content = req.getCustomTemplateContent();
                if (content.length() > 8000) {
                    content = content.substring(0, 8000);
                }
                root.put("customTemplateContent", content);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建 PRD 生成任务参数失败", e);
        }
    }

    @Override
    public Long submitXmind(String fileName, String outlineText, String template, String detailLevel, Long userId) {
        return submitXmind(fileName, outlineText, template, detailLevel, userId, null, null);
    }

    @Override
    public Long submitXmind(String fileName, String outlineText, String template, String detailLevel, Long userId,
                            String customTemplateContent, String customTemplateFileName) {
        String featureName = fileName.replaceAll("\\.xmind$", "");

        PrdGenerateRequest request = new PrdGenerateRequest();
        request.setFeatureName(featureName);
        request.setDescription(outlineText);
        request.setTemplate(parseEnumOrDefault(
                com.example.aidocumentplatform.model.enums.TemplateType.class,
                template,
                com.example.aidocumentplatform.model.enums.TemplateType.STANDARD,
                "template"));
        request.setDetailLevel(parseEnumOrDefault(
                com.example.aidocumentplatform.model.enums.DetailLevel.class,
                detailLevel,
                com.example.aidocumentplatform.model.enums.DetailLevel.DETAILED,
                "detailLevel"));
        request.setCustomTemplateContent(customTemplateContent);
        request.setCustomTemplateFileName(customTemplateFileName);

        String inputParams;
        try {
            ObjectNode root = objectMapper.readValue(buildInputJson(request), ObjectNode.class);
            root.put("source", "XMIND");
            root.put("fileName", fileName);
            root.put("outline", outlineText);
            inputParams = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建 XMind 任务参数失败", e);
        }

        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PRD_GENERATE, inputParams);
        AsyncTask task = reservation.task();
        log.info("XMind PRD generation task prepared: taskId={}, userId={}, fileName={}, template={}",
                task.getId(), userId, fileName, template);

        if (reservation.created()) {
            applicationContext.getBean(PrdGenerateServiceImpl.class).execute(task.getId(), request, userId);
        }
        return task.getId();
    }

    private <E extends Enum<E>> E parseEnumOrDefault(Class<E> enumClass, String value, E defaultValue, String fieldName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("参数 " + fieldName + " 无效: " + value);
        }
    }
}
