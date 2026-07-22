package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.prompt.PrdEnhancePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrdEnhanceRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.enums.DocumentSourceType;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.service.PrdEnhanceService;
import com.example.aidocumentplatform.util.WordReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final TaskServiceImpl taskService;
    private final WordReader wordReader;

    @Override
    public Long submit(PrdEnhanceRequest request, Long userId) {
        String prdContent = resolvePrdContent(request);
        AsyncTask task = createTask(userId, prdContent, request.getContentTypes(), null);
        execute(task.getId(), prdContent, null, request.getContentTypes(), request.getInstruction(), userId);
        return task.getId();
    }

    @Override
    public Long submitWithWord(PrdEnhanceRequest request, byte[] wordBytes, String fileName, Long userId) {
        String wordContent = wordReader.extractText(wordBytes);
        String prdContent = resolvePrdContent(request);
        AsyncTask task = createTask(userId, prdContent, request.getContentTypes(), fileName);
        execute(task.getId(), prdContent, wordContent, request.getContentTypes(), request.getInstruction(), userId);
        return task.getId();
    }

    /** 解析 PRD 内容：优先从 prdDocumentId 查，否则用 prdContent */
    private String resolvePrdContent(PrdEnhanceRequest req) {
        if (req.getPrdDocumentId() != null) {
            return prdDocumentRepository.findById(req.getPrdDocumentId())
                    .map(p -> (p.getDescription() != null ? p.getDescription() + "\n" : "") + p.getTitle())
                    .orElse(req.getPrdContent());
        }
        return req.getPrdContent();
    }

    private AsyncTask createTask(Long userId, String prdContent, List<String> types, String fileName) {
        String inputJson = "{\"contentTypes\":" + (types != null ? types.toString() : "[]") +
                (fileName != null ? ",\"wordFile\":\"" + fileName.replace("\"", "\\\"") + "\"" : "") + "}";
        AsyncTask task = AsyncTask.builder()
                .userId(userId).taskType(TaskType.PRD_ENHANCE).status(TaskStatus.PENDING)
                .inputParams(inputJson).build();
        task = asyncTaskRepository.save(task);
        log.info("PRD增强任务已创建: taskId={}, userId={}, contentTypes={}", task.getId(), userId, types);
        return task;
    }

    @Async("asyncTaskExecutor")
    public void execute(Long taskId, String prdContent, String wordContent,
                         List<String> contentTypes, String instruction, Long userId) {
        AsyncTask task = asyncTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus(TaskStatus.RUNNING); asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 10, "AI 正在生成增强内容...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(prdContent, wordContent, contentTypes, instruction);
            String aiResponse = aiClient.generate(systemPrompt, userPrompt);

            taskService.pushProgress(taskId, 70, "AI 生成完成，正在保存...");
            String jsonContent = extractJson(aiResponse);

            // 存入 prd_document（增强结果也作为 PRD 文档的一条记录）
            PrdDocument doc = PrdDocument.builder()
                    .userId(userId).taskId(taskId)
                    .title("增强结果")
                    .description("原始内容长度: " + (prdContent != null ? prdContent.length() : 0))
                    .content(jsonContent)
                    .sourceType(DocumentSourceType.MANUAL)
                    .build();
            doc = prdDocumentRepository.save(doc);

            task.setResultRefId(doc.getId());
            task.setStatus(TaskStatus.SUCCESS);
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 100, "PRD 增强完成");
            log.info("PRD增强成功: taskId={}, documentId={}", taskId, doc.getId());
        } catch (Exception e) {
            log.error("PRD增强失败: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 500));
            asyncTaskRepository.save(task);
            taskService.pushProgress(taskId, 0, "增强失败: " + e.getMessage());
        }
    }

    private String extractJson(String s) {
        if (s == null) return "{}";
        s = s.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }
    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }
}
