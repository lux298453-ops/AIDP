package com.example.aidocumentplatform.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 辅助修改原型的返回数据（包裹在 {@link ApiResponse#getData()} 中）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrototypeAiEditResponse {

    /** 被修改的原型 ID */
    private Long prototypeId;

    /** 修改后的完整 HTML，用于前端直接刷新 iframe */
    private String newHtml;

    /** 一句话中文修改说明，用于前端提示用户（如「已修改登录按钮颜色并新增返回按钮」） */
    private String changeSummary;
}
