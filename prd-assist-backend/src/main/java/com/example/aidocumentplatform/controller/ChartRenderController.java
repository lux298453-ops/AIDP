package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.ChartRenderRequest;
import com.example.aidocumentplatform.util.MermaidImageRenderer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图表渲染接口：前端详情页展示图表时调用，
 * 由后端统一渲染 PlantUML（优先）/ Mermaid（兜底）为 PNG。
 *
 * POST /api/charts/render → image/png
 */
@Slf4j
@RestController
@RequestMapping("/api/charts")
@RequiredArgsConstructor
public class ChartRenderController {

    private final MermaidImageRenderer mermaidImageRenderer;

    @PostMapping("/render")
    public ResponseEntity<byte[]> render(@Valid @RequestBody ChartRenderRequest request) {
        if (!mermaidImageRenderer.isEnabled()) {
            return ResponseEntity.status(503).build();
        }
        byte[] png = mermaidImageRenderer.renderByLang(request.getLang(), request.getCode());
        if (png == null) {
            log.warn("图表渲染失败: lang={}, sourceLen={}",
                    request.getLang(), request.getCode() == null ? 0 : request.getCode().length());
            return ResponseEntity.status(422).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }
}
