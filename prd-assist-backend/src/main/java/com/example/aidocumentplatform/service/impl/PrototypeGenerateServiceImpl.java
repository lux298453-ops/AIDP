package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    public Long submit(PrototypeGenerateRequest request, Long userId) {
        AsyncTask task = AsyncTask.builder()
                .userId(userId).taskType(TaskType.PROTOTYPE).status(TaskStatus.PENDING)
                .inputParams("{\"platform\":\"" + request.getPlatform().name() + "\"" +
                        ",\"prototypeType\":\"" + request.getPrototypeType().name() + "\"" +
                        ",\"description\":\"" + esc(request.getDescription()) + "\"}")
                .build();
        task = asyncTaskRepository.save(task);
        log.info("原型生成任务: taskId={}, type={}, platform={}", task.getId(), request.getPrototypeType(), request.getPlatform());
        execute(task.getId(), request, userId);
        return task.getId();
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, PrototypeGenerateRequest request, Long userId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus(TaskStatus.RUNNING); asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 15, "AI 正在生成原型...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    request.getDescription(), request.getPlatform(), request.getPrototypeType());
            String aiOutput = aiClient.generate(systemPrompt, userPrompt);

            taskService.pushProgress(taskId, 60, "代码生成完成，正在清洗...");

            // 根据类型清洗输出
            String content = request.getPrototypeType() == PrototypeType.MULTI_PAGE
                    ? cleanJson(aiOutput)   // 多页面: JSON 数组
                    : cleanHtml(aiOutput);  // 单页面: 纯 HTML

            taskService.pushProgress(taskId, 85, "正在保存原型...");

            PrototypeResult proto = PrototypeResult.builder()
                    .userId(userId).taskId(taskId).prdDocumentId(request.getPrdDocumentId())
                    .prototypeType(request.getPrototypeType()).platform(request.getPlatform())
                    .content(content).build();
            proto = prototypeResultRepository.save(proto);

            task.setResultRefId(proto.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 100, "原型生成完成");
            log.info("原型生成成功: taskId={}, resultId={}, type={}, len={}",
                    taskId, proto.getId(), request.getPrototypeType(), content.length());

        } catch (Exception e) {
            log.error("原型生成失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "生成失败: " + e.getMessage());
        }
    }

    // ==================== HTML 清洗（单页面） ====================

    private String cleanHtml(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```html")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        int lt = s.indexOf('<'), end = s.lastIndexOf('>');
        if (lt > 0) s = s.substring(lt);
        if (end > 0 && end < s.length() - 1) s = s.substring(0, end + 1);
        return s;
    }

    // ==================== JSON 清洗（多页面） ====================

    private String cleanJson(String raw) {
        if (raw == null) return "[]";
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        int start = s.indexOf('['), end = s.lastIndexOf(']');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    private String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }
}
