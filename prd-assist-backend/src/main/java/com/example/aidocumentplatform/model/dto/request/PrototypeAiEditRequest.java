package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 辅助修改原型的请求体。
 *
 * POST /api/prototype/{id}/ai-edit
 */
@Data
public class PrototypeAiEditRequest {

    /** 用户的自然语言修改描述，必填。例如「把登录按钮改成蓝色，标题加大一号」 */
    @NotBlank(message = "修改描述不能为空")
    @Size(max = 1000, message = "修改描述最多1000字")
    private String instruction;

    /**
     * 可选：指定要修改的元素（如 CSS 选择器或元素描述）。
     * 为空表示由 AI 根据描述自行定位。
     */
    private String targetElement;

    /**
     * 多页原型当前页索引。单页原型忽略。
     */
    private Integer pageIndex;

    /**
     * 可选：前端可视化编辑后的「最新 HTML」。
     * 若提供则以此为基准修改（保证包含用户手动微调的内容）；
     * 为空则使用数据库中存储的原型内容。
     */
    private String currentHtml;
}
