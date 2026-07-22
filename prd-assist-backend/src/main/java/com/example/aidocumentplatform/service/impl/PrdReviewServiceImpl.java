package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.prompt.PrdReviewPromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.service.PrdReviewService;
import com.example.aidocumentplatform.util.PrdContentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrdReviewServiceImpl implements PrdReviewService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final AiClient aiClient;
    private final PrdReviewPromptTemplate promptTemplate;
    private final TaskServiceImpl taskService;
    private final PrdContentParser prdContentParser;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Long submit(PrdReviewRequest request, Long userId) {
        String prdContent = resolvePrdContent(request, userId);
        Long prdDocId = request.getPrdDocumentId();
        if (prdDocId == null) prdDocId = 0L; // 无关联 PRD 时用 0 占位

        // 维度 JSON
        List<String> dims = request.getDimensions();
        String dimsJson = toJson(dims != null ? dims : List.of());

        AsyncTask task = AsyncTask.builder()
                .userId(userId).taskType(TaskType.PRD_REVIEW).status(TaskStatus.PENDING)
                .inputParams("{\"dimensions\":" + dimsJson + ",\"requirement\":\"" +
                        (request.getRequirement() != null ? esc(request.getRequirement()) : "") + "\"}")
                .build();
        task = asyncTaskRepository.save(task);
        log.info("PRD审查任务已创建: taskId={}, userId={}, dimensions={}", task.getId(), userId, dims);
        // 通过 Spring 代理调用，确保 @Async 生效
        applicationContext.getBean(PrdReviewServiceImpl.class)
                .execute(task.getId(), prdContent, dims, request.getRequirement(), userId, prdDocId);
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, String prdContent, List<String> dimensions, String requirement, Long userId, Long prdDocId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus(TaskStatus.RUNNING); asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 10, "AI 正在审查 PRD...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(prdContent, dimensions, requirement);
            String aiResponse = aiClient.generate(systemPrompt, userPrompt);

            taskService.pushProgress(taskId, 70, "审查完成，正在保存报告...");

            String json = prdContentParser.parseObject(aiResponse).toString();
            String dimsJson = toJson(dimensions != null ? dimensions : List.of());

            ReviewReport report = ReviewReport.builder()
                    .userId(userId).prdDocumentId(prdDocId).taskId(taskId)
                    .dimensions(dimsJson).issues(json).build();
            report = reviewReportRepository.save(report);

            task.setResultRefId(report.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 100, "审查完成");
            log.info("PRD审查成功: taskId={}, reportId={}", taskId, report.getId());
        } catch (Exception e) {
            log.error("PRD审查失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "审查失败: " + e.getMessage());
        }
    }

    private String resolvePrdContent(PrdReviewRequest req, Long userId) {
        if (req.getPrdDocumentId() != null) {
            var document = prdDocumentRepository.findById(req.getPrdDocumentId())
                    .orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
            if (!document.getUserId().equals(userId)) throw new IllegalArgumentException("无权访问该 PRD");
            return document.getTitle() + "\n" + (document.getDescription() != null ? document.getDescription() + "\n" : "")
                    + "\n【结构化 PRD 内容】\n" + document.getContent();
        }
        return req.getPrdContent();
    }
    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("无法序列化任务参数", e); }
    }
    private String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }
}
