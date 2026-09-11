package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrdEnhanceRequest;
import com.example.aidocumentplatform.model.dto.request.PrdGenerateRequest;
import com.example.aidocumentplatform.model.dto.request.PrdReviewFixRequest;
import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.enums.AssetMode;
import com.example.aidocumentplatform.model.enums.DetailLevel;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.model.enums.TemplateType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    @SuppressWarnings("unchecked")
    public Long regenerate(Long oldTaskId, Long userId) {
        AsyncTask oldTask = asyncTaskRepository.findById(oldTaskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + oldTaskId));
        if (!oldTask.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作此任务");
        }

        TaskType type = oldTask.getTaskType();
        String inputJson = oldTask.getInputParams();
        Map<String, Object> params = parseParams(inputJson);

        log.info("重新生成: oldTaskId={}, type={}, userId={}", oldTaskId, type, userId);

        return switch (type) {
            case PRD_GENERATE -> {
                PrdGenerateRequest req = new PrdGenerateRequest();
                req.setFeatureName(str(params, "featureName", "重新生成"));
                String desc = str(params, "description", null);
                if (desc == null || desc.isBlank()) {
                    desc = str(params, "outline", "");
                }
                req.setDescription(desc);
                req.setTemplate(parseEnumOrDefault(TemplateType.class, str(params, "template", null), TemplateType.STANDARD, "template"));
                req.setDetailLevel(parseEnumOrDefault(DetailLevel.class, str(params, "detailLevel", null), DetailLevel.DETAILED, "detailLevel"));
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
                req.setPlatform(parseEnumOrDefault(Platform.class, str(params, "platform", "APP"), Platform.APP, "platform"));
                req.setPrototypeType(parseEnumOrDefault(PrototypeType.class, str(params, "prototypeType", "SINGLE_PAGE"), PrototypeType.SINGLE_PAGE, "prototypeType"));
                req.setAssetMode(parseEnumOrDefault(AssetMode.class, str(params, "assetMode", "AUTO"), AssetMode.AUTO, "assetMode"));
                req.setClarificationAnswers(list(params, "clarificationAnswers"));
                req.setGenerationBrief(str(params, "generationBrief", null));
                Object assetPlan = params.get("assetPlan");
                if (assetPlan != null) {
                    req.setAssetPlan(objectMapper.convertValue(assetPlan, PrototypeAssetPlan.class));
                }
                Object assetPlans = params.get("assetPlans");
                if (assetPlans != null) {
                    req.setAssetPlans(objectMapper.convertValue(
                            assetPlans,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, PrototypeAssetPlan.class)));
                }
                yield prototypeGenerateService.submit(req, userId);
            }
            case PRD_REVIEW -> {
                PrdReviewRequest req = new PrdReviewRequest();
                req.setPrdContent(str(params, "prdContent", ""));
                String prdDocId = str(params, "prdDocumentId", null);
                if (prdDocId != null && !prdDocId.isBlank()) {
                    try {
                        req.setPrdDocumentId(Long.parseLong(prdDocId));
                    } catch (Exception ignored) {
                    }
                }
                req.setDimensions(list(params, "dimensions"));
                req.setRequirement(str(params, "requirement"));
                yield prdReviewService.submit(req, userId);
            }
            case PRD_REVIEW_FIX -> {
                String reportIdStr = str(params, "reportId", null);
                if (reportIdStr == null || reportIdStr.isBlank()) {
                    throw new RuntimeException("无法重新执行修复：缺少 reportId");
                }
                Long reportId = Long.parseLong(reportIdStr);
                yield prdReviewService.submitFix(reportId, new PrdReviewFixRequest(), userId);
            }
            case PROTOTYPE_AI_EDIT -> throw new RuntimeException("原型局部 AI 修改任务不支持重新执行，请在原型预览页重新输入修改描述");
        };
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            String cleaned = json.replace("\\\"", "\"").replace("\"{", "{").replace("}\"", "}");
            try {
                return objectMapper.readValue(cleaned, Map.class);
            } catch (Exception ex) {
                return Map.of();
            }
        }
    }

    private String str(Map<String, Object> m, String key) {
        return str(m, key, "");
    }

    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private <E extends Enum<E>> E parseEnumOrDefault(Class<E> enumClass, String value, E defaultValue, String fieldName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("任务参数中的 " + fieldName + " 无效: " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List) return (List<String>) v;
        if (v instanceof String && ((String) v).startsWith("[")) {
            try {
                return objectMapper.readValue((String) v, List.class);
            } catch (Exception ignored) {
            }
        }
        return Collections.emptyList();
    }
}
