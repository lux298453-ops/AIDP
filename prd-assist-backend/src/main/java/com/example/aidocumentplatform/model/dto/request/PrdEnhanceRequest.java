package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * PRD 增强请求体。
 */
@Data
public class PrdEnhanceRequest {

    /** 原始 PRD 内容（直接粘贴），与 prdDocumentId 二选一 */
    @Size(max = 5000, message = "PRD内容最多5000字")
    private String prdContent;

    /** 引用已有 PRD 文档的 ID，与 prdContent 二选一 */
    private Long prdDocumentId;

    /** 要补充的内容类型: structure / flow / data / testcase */
    private List<String> contentTypes;

    /** 自定义增强指令（可选） */
    @Size(max = 500, message = "增强指令最多500字")
    private String instruction;
}
