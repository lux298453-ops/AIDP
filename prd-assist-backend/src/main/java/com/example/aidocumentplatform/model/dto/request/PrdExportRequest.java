package com.example.aidocumentplatform.model.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 带图表图片的 PRD Word 导出请求。
 * chartImages 由前端将 Mermaid 渲染为 PNG 后以 base64 提交。
 */
@Data
public class PrdExportRequest {

    private List<ChartImage> chartImages;

    @Data
    public static class ChartImage {
        /** 与 chapters 下标对应，如 0、1；也可用 "summary" */
        private String key;
        /** PNG base64，可带或不带 data:image/png;base64, 前缀 */
        private String pngBase64;
    }
}
