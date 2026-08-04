package com.example.aidocumentplatform.task;

import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.service.AiService;
import com.example.aidocumentplatform.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutor {

    private final AsyncTaskRepository asyncTaskRepository;
    private final TaskService taskService;
    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async("asyncTaskExecutor")
    public void executeTask(Long taskId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.error("任务不存在: {}", taskId);
            return;
        }

        try {
            task.setStatus(TaskStatus.RUNNING);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 10, "任务开始执行");

            String result = switch (task.getTaskType()) {
                case PRD_GENERATE -> aiService.generatePrd(task.getInputParams());
                case PRD_ENHANCE -> {
                    String prdContent = extractJsonField(task.getInputParams(), "prdContent");
                    String instruction = extractJsonField(task.getInputParams(), "instruction");
                    yield aiService.enhancePrd(prdContent, instruction);
                }
                case PRD_REVIEW -> aiService.reviewPrd(task.getInputParams());
                case PROTOTYPE -> aiService.generatePrototype(task.getInputParams());
                case PRD_REVIEW_FIX -> throw new UnsupportedOperationException(
                        "PRD_REVIEW_FIX 应由 PrdReviewService 异步执行，不支持通用 TaskExecutor");
                case PROTOTYPE_AI_EDIT -> throw new UnsupportedOperationException(
                        "PROTOTYPE_AI_EDIT 应由 PrototypeAiEditService 异步执行，不支持通用 TaskExecutor");
            };

            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);

            taskService.pushProgress(taskId, 100, "任务完成");
            log.info("任务执行成功: {}, resultLen={}", taskId, result == null ? 0 : result.length());

        } catch (Exception e) {
            log.error("任务执行失败: {}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "任务失败: " + e.getMessage());
        }
    }

    private String extractJsonField(String json, String field) {
        if (json == null || json.isBlank() || field == null || field.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode node = root.path(field);
            return node.isMissingNode() || node.isNull() ? "" : node.asText("");
        } catch (Exception e) {
            log.warn("提取任务字段失败: field={}, cause={}", field, e.getMessage());
            return "";
        }
    }
}