package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * PRD 审查请求体。
 */
@Data
public class PrdReviewRequest {

    /** 原始 PRD 内容（直接粘贴），与 prdDocumentId 二选一 */
    @Size(max = 50000, message = "PRD内容最多50000字")
    private String prdContent;

    /** 引用已有 PRD 文档 ID */
    private Long prdDocumentId;

    /** 审查维度: completeness / consistency / compliance */
    private List<String> dimensions;

    /** 自定义审查要求（可选） */
    @Size(max = 500, message = "审查要求最多500字")
    private String requirement;
}
