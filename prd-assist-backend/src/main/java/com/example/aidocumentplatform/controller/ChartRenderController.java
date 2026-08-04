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

@Slf4j
@RestController
@RequestMapping("/api/charts")
@RequiredArgsConstructor
public class ChartRenderController {

    private static final MediaType SVG_MEDIA_TYPE = MediaType.valueOf("image/svg+xml");

    private final MermaidImageRenderer mermaidImageRenderer;

    @PostMapping("/render")
    public ResponseEntity<byte[]> render(@Valid @RequestBody ChartRenderRequest request) {
        if (!mermaidImageRenderer.isEnabled()) {
            return ResponseEntity.status(503).build();
        }

        String format = normalizeFormat(request.getFormat());
        if ("svg".equals(format)) {
            byte[] svg = mermaidImageRenderer.renderSvgByLang(request.getLang(), request.getCode());
            if (svg == null) {
                log.warn("图表 SVG 渲染失败: lang={}, sourceLen={}",
                        request.getLang(), request.getCode() == null ? 0 : request.getCode().length());
                return ResponseEntity.status(422).build();
            }
            return ResponseEntity.ok()
                    .contentType(SVG_MEDIA_TYPE)
                    .body(svg);
        }

        byte[] png = mermaidImageRenderer.renderByLang(request.getLang(), request.getCode());
        if (png == null) {
            log.warn("图表 PNG 渲染失败: lang={}, sourceLen={}",
                    request.getLang(), request.getCode() == null ? 0 : request.getCode().length());
            return ResponseEntity.status(422).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "png";
        }
        return format.trim().toLowerCase();
    }
}
