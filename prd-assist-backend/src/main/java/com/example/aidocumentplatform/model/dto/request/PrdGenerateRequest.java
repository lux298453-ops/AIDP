package com.example.aidocumentplatform.model.dto.request;

import com.example.aidocumentplatform.model.enums.DetailLevel;
import com.example.aidocumentplatform.model.enums.TemplateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PRD 生成请求体。
 */
@Data
public class PrdGenerateRequest {

    /** 功能名称，必填，最多 50 字 */
    @NotBlank(message = "功能名不能为空")
    @Size(max = 50, message = "功能名最多50字")
    private String featureName;

    /** 需求描述，最多 500 字 */
    @Size(max = 500, message = "需求描述最多500字")
    private String description;

    /** 模板类型，默认 STANDARD */
    private TemplateType template = TemplateType.STANDARD;

    /** 详略程度，CONCISE 或 DETAILED */
    private DetailLevel detailLevel = DetailLevel.DETAILED;
}
