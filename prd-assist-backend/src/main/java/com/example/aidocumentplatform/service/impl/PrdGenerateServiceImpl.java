package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.prompt.PrdGeneratePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrdGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.enums.DocumentSourceType;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.service.PrdGenerateService;
import com.example.aidocumentplatform.util.PrdContentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * PRD 生成服务实现。
 *
 * 流程:
 *   1. submit() 创建 async_task(status=PENDING) → 返回 taskId
 *   2. @Async execute() 在后台线程中运行:
 *      a. 调 AiClient.generate() 获取 AI 输出
 *      b. 解析结果 → 保存到 prd_document
 *      c. 更新 async_task.resultRefId + status=SUCCESS
 *      d. 失败时更新 status=FAILED + errorMessage
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrdGenerateServiceImpl implements PrdGenerateService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final AiClient aiClient;
    private final PrdGeneratePromptTemplate promptTemplate;
    private final TaskServiceImpl taskService;
    private final PrdContentParser prdContentParser;
    private final ApplicationContext applicationContext;

    @Override
    public Long submit(PrdGenerateRequest request, Long userId) {
        // 1. 创建 PENDING 任务
        AsyncTask task = AsyncTask.builder()
                .userId(userId)
                .taskType(TaskType.PRD_GENERATE)
                .status(TaskStatus.PENDING)
                .inputParams(buildInputJson(request))
                .build();
        task = asyncTaskRepository.save(task);

        log.info("PRD生成任务已创建: taskId={}, userId={}, featureName={}", task.getId(), userId, request.getFeatureName());

        // 2. 异步执行生成（通过 Spring 代理确保 @Async 生效）
        applicationContext.getBean(PrdGenerateServiceImpl.class).execute(task.getId(), request, userId);

        return task.getId();
    }

    /**
     * 后台异步执行 PRD 生成。
     */
    @Async("asyncTaskExecutor")
    public void execute(Long taskId, PrdGenerateRequest request, Long userId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        try {
            // 更新为 RUNNING
            task.setStatus(TaskStatus.RUNNING);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 10, "AI 正在生成 PRD...");

            // 调用 AI
            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    request.getFeatureName(), request.getDescription(), request.getDetailLevel());
            log.info("AI 调用开始: taskId={}, promptLen={}", taskId, userPrompt.length());
            String aiResponse = aiClient.generate(systemPrompt, userPrompt);
            log.info("AI 调用完成: taskId={}, responseLen={}, firstChars={}",
                    taskId, aiResponse.length(),
                    aiResponse.length() > 200 ? aiResponse.substring(0, 200) : aiResponse);

            taskService.pushProgress(taskId, 70, "AI 生成完成，正在保存...");

            // 提取 JSON 内容（AI 可能包裹在 markdown 代码块中）
            String jsonContent = prdContentParser.normalizeToJson(aiResponse);

            // 保存到 prd_document（description 限制 500 字符，XMind 大纲文本可能很长）
            String safeDescription = request.getDescription() != null && request.getDescription().length() > 500
                    ? request.getDescription().substring(0, 497) + "..."
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
            doc = prdDocumentRepository.save(doc);

            // 更新任务为 SUCCESS
            task.setResultRefId(doc.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);

            taskService.pushProgress(taskId, 100, "PRD 生成完成");
            log.info("PRD生成成功: taskId={}, documentId={}", taskId, doc.getId());

        } catch (Exception e) {
            log.error("PRD生成失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "生成失败: " + e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private String buildInputJson(PrdGenerateRequest req) {
        return "{\"featureName\":\"" + escape(req.getFeatureName()) + "\"" +
                ",\"description\":\"" + escape(req.getDescription()) + "\"" +
                ",\"template\":\"" + req.getTemplate().name() + "\"" +
                ",\"detailLevel\":\"" + req.getDetailLevel().name() + "\"}";
    }

    @Override
    public Long submitXmind(String fileName, String outlineText, String template, String detailLevel, Long userId) {
        String featureName = fileName.replaceAll("\\.xmind$", "");
        String inputParams = "{\"source\":\"XMIND\",\"fileName\":\"" + escape(fileName) + "\"" +
                ",\"outline\":\"" + escape(outlineText) + "\"" +
                ",\"template\":\"" + template + "\"" +
                ",\"detailLevel\":\"" + detailLevel + "\"}";

        AsyncTask task = AsyncTask.builder()
                .userId(userId)
                .taskType(TaskType.PRD_GENERATE)
                .status(TaskStatus.PENDING)
                .inputParams(inputParams)
                .build();
        task = asyncTaskRepository.save(task);
        log.info("XMind PRD生成任务已创建: taskId={}, userId={}, fileName={}", task.getId(), userId, fileName);

        // 构建 request 并异步执行
        PrdGenerateRequest request = new PrdGenerateRequest();
        request.setFeatureName(featureName);
        request.setDescription(outlineText);
        // 用字符串匹配枚举
        try { request.setTemplate(com.example.aidocumentplatform.model.enums.TemplateType.valueOf(template)); } catch (Exception ignored) {}
        try { request.setDetailLevel(com.example.aidocumentplatform.model.enums.DetailLevel.valueOf(detailLevel)); } catch (Exception ignored) {}

        // 通过 Spring 代理调用，确保 @Async 生效
        applicationContext.getBean(PrdGenerateServiceImpl.class).execute(task.getId(), request, userId);
        return task.getId();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
