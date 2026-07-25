package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.*;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.enums.*;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 任务重新生成服务。
 *
 * 读取原 async_task 的 inputParams，创建新任务并触发对应模块的异步生成。
 * 旧任务和旧结果保持不变（版本历史保留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRegenerateService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrdGenerateService prdGenerateService;
    private final PrdEnhanceService prdEnhanceService;
    private final PrototypeGenerateService prototypeGenerateService;
    private final PrdReviewService prdReviewService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 基于已有任务重新生成。
     *
     * @param oldTaskId 原始任务 ID
     * @param userId    当前用户 ID（需与原任务一致）
     * @return 新任务 ID
     */
    @SuppressWarnings("unchecked")
    public Long regenerate(Long oldTaskId, Long userId) {
        AsyncTask oldTask = asyncTaskRepository.findById(oldTaskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + oldTaskId));
        if (!oldTask.getUserId().equals(userId))
            throw new RuntimeException("无权操作此任务");

        TaskType type = oldTask.getTaskType();
        String inputJson = oldTask.getInputParams();
        Map<String, Object> params = parseParams(inputJson);

        log.info("重新生成: oldTaskId={}, type={}, userId={}", oldTaskId, type, userId);

        return switch (type) {
            case PRD_GENERATE -> {
                PrdGenerateRequest req = new PrdGenerateRequest();
                req.setFeatureName(str(params, "featureName", "重新生成"));
                // XMind 源任务 description 可能落在 outline 字段
                String desc = str(params, "description", null);
                if (desc == null || desc.isBlank()) desc = str(params, "outline", "");
                req.setDescription(desc);
                req.setTemplate(safeEnum(TemplateType.class, str(params, "template")));
                req.setDetailLevel(safeEnum(DetailLevel.class, str(params, "detailLevel")));
                req.setCustomTemplateContent(str(params, "customTemplateContent", null));
                req.setCustomTemplateFileName(str(params, "customTemplateFileName", null));
                yield prdGenerateService.submit(req, userId);
            }
            case PRD_ENHANCE -> {
                PrdEnhanceRequest req = new PrdEnhanceRequest();
                req.setPrdContent(str(params, "prdContent", ""));
                req.setContentTypes(list(params, "contentTypes"));
                req.setInstruction(str(params, "instruction"));
                yield prdEnhanceService.submit(req, userId);
            }
            case PROTOTYPE -> {
                PrototypeGenerateRequest req = new PrototypeGenerateRequest();
                req.setDescription(str(params, "description", ""));
                req.setPlatform(safeEnum(Platform.class, str(params, "platform", "APP")));
                req.setPrototypeType(safeEnum(PrototypeType.class, str(params, "prototypeType", "SINGLE_PAGE")));
                yield prototypeGenerateService.submit(req, userId);
            }
            case PRD_REVIEW -> {
                PrdReviewRequest req = new PrdReviewRequest();
                req.setPrdContent(str(params, "prdContent", ""));
                String prdDocId = str(params, "prdDocumentId", null);
                if (prdDocId != null && !prdDocId.isBlank()) {
                    try { req.setPrdDocumentId(Long.parseLong(prdDocId)); } catch (Exception ignored) {}
                }
                req.setDimensions(list(params, "dimensions"));
                req.setRequirement(str(params, "requirement"));
                yield prdReviewService.submit(req, userId);
            }
            case PRD_REVIEW_FIX -> {
                // 修复任务：基于原报告再跑一遍修复
                String reportIdStr = str(params, "reportId", null);
                if (reportIdStr == null || reportIdStr.isBlank()) {
                    throw new RuntimeException("无法重新执行修复：缺少 reportId");
                }
                Long reportId = Long.parseLong(reportIdStr);
                yield prdReviewService.submitFix(reportId, new PrdReviewFixRequest(), userId);
            }
        };
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            // inputParams 可能被嵌套转义，尝试解析
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            // 如果 JSON 被过度转义（如 "\"{\\\"key\\\":...}\""），手动提取
            String cleaned = json.replace("\\\"", "\"").replace("\"{", "{").replace("}\"", "}");
            try { return objectMapper.readValue(cleaned, Map.class); } catch (Exception ex) { return Map.of(); }
        }
    }

    private String str(Map<String, Object> m, String key) { return str(m, key, ""); }
    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }
    private <E extends Enum<E>> E safeEnum(Class<E> enumClass, String value) {
        try { return Enum.valueOf(enumClass, value); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List) return (List<String>) v;
        if (v instanceof String && ((String) v).startsWith("[")) {
            try { return objectMapper.readValue((String) v, List.class); } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }
}
