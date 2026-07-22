package com.example.aidocumentplatform.model.dto.request;

import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 原型生成请求体。
 */
@Data
public class PrototypeGenerateRequest {

    /** 功能描述，必填 */
    @NotBlank(message = "功能描述不能为空")
    @Size(max = 2000, message = "功能描述最多2000字")
    private String description;

    /** 原型类型 */
    private PrototypeType prototypeType = PrototypeType.SINGLE_PAGE;

    /** 终端类型 */
    private Platform platform = Platform.APP;

    /** 关联的 PRD 文档 ID（可选） */
    private Long prdDocumentId;
}
