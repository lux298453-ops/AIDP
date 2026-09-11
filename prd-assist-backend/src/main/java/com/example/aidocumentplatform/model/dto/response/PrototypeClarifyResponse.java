package com.example.aidocumentplatform.model.dto.response;

import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrototypeClarifyResponse {

    private boolean needsClarification;

    private String intentSummary;

    /** AI 根据原始需求形成的内部生成简报，后续原型生成必须继续携带。 */
    private String generationBrief;

    /** 系统内部素材计划，前端只负责随生成请求原样带回。 */
    private PrototypeAssetPlan assetPlan;

    /** 多页面独立素材计划；单页面为空，最多 3 项。 */
    private List<PrototypeAssetPlan> assetPlans;

    private List<Question> questions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private String id;
        private String title;
        private String prompt;
        private List<Option> options;
        private boolean allowCustomInput;
        private String customInputPlaceholder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Option {
        private String value;
        private String label;
        private String description;
    }
}
