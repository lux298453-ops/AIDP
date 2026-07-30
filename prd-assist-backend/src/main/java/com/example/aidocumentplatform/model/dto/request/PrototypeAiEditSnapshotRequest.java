package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 原型 AI 流式修改完成后，前端把 iframe 中的最终 HTML 回存到服务端。
 */
@Data
public class PrototypeAiEditSnapshotRequest {

    /** 前端已应用所有 patch 后导出的完整 HTML */
    @NotBlank(message = "原型 HTML 不能为空")
    private String currentHtml;

    /** 多页原型当前页索引。单页原型忽略。 */
    private Integer pageIndex;

    /** 本次修改说明，用于前端提示。 */
    private String changeSummary;
}
