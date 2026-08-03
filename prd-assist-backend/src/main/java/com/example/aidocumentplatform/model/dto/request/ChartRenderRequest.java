package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 前端图表渲染请求：把 PlantUML / Mermaid 源码交给后端渲染成 PNG。
 */
@Data
public class ChartRenderRequest {

    /** 图表源码（PlantUML 或 Mermaid） */
    @NotBlank(message = "图表源码不能为空")
    private String code;

    /** 源码语言：plantuml / mermaid；为空时自动识别 */
    private String lang;
}
