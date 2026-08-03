package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 修订章节中的 Mermaid 图表。
 */
@Data
public class ChartReviseRequest {

    /** 章节下标（0-based，对应 content.chapters） */
    @NotNull(message = "chapterIndex 不能为空")
    private Integer chapterIndex;

    /** 用户自然语言修改描述 */
    @NotBlank(message = "请描述修改需求")
    private String instruction;

    /** 可选：前端当前编辑中的图表源码（未保存时优先使用；PlantUML 或 Mermaid） */
    private String currentCode;
}
