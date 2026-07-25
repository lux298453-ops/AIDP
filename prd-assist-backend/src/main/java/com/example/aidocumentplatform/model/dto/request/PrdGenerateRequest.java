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

    /** 需求描述，最多 50000 字 */
    @Size(max = 50000, message = "需求描述最多50000字")
    private String description;

    /** 模板类型，默认 STANDARD；CUSTOM 时需配合 customTemplateContent */
    private TemplateType template = TemplateType.STANDARD;

    /** 详略程度，CONCISE 或 DETAILED */
    private DetailLevel detailLevel = DetailLevel.DETAILED;

    /**
     * 自定义模板的纯文本内容（.docx 解析后写入）。
     * template=CUSTOM 时必填，由 Controller 从 customTemplateFile 提取后注入。
     */
    private String customTemplateContent;

    /** 自定义模板原始文件名（可选，仅用于记录/日志） */
    private String customTemplateFileName;
}
