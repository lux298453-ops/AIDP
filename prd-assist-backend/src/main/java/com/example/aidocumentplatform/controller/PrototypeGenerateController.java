package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.common.FileStorage;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
import com.example.aidocumentplatform.util.ImageUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 原型生成接口。
 *
 * POST /api/prototype/generate    →  JSON 或 multipart（可带 referenceImage）
 * GET  /api/prototype/{id}        →  获取原型 HTML 内容
 * PUT  /api/prototype/{id}        →  保存编辑
 * GET  /api/prototype/{id}/export →  导出 HTML
 */
@Slf4j
@RestController
@RequestMapping("/api/prototype")
@RequiredArgsConstructor
public class PrototypeGenerateController {

    private final PrototypeGenerateService prototypeGenerateService;
    private final PrototypeResultRepository prototypeResultRepository;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** JSON 提交（无参考图） */
    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Long>> generate(@Valid @RequestBody PrototypeGenerateRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prototypeGenerateService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /**
     * multipart 提交（可带风格参考图）。
     * 字段：description, prototypeType, platform, prdDocumentId?, referenceImage?
     */
    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Long>> generateWithImage(
            @RequestParam("description") String description,
            @RequestParam(value = "prototypeType", defaultValue = "SINGLE_PAGE") String prototypeType,
            @RequestParam(value = "platform", defaultValue = "APP") String platform,
            @RequestParam(value = "prdDocumentId", required = false) Long prdDocumentId,
            @RequestParam(value = "referenceImage", required = false) MultipartFile referenceImage
    ) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("功能描述不能为空");
        }
        if (description.length() > 50000) {
            throw new IllegalArgumentException("功能描述最多50000字");
        }

        PrototypeGenerateRequest request = new PrototypeGenerateRequest();
        request.setDescription(description.trim());
        request.setPrototypeType(parsePrototypeType(prototypeType));
        request.setPlatform(parsePlatform(platform));
        request.setPrdDocumentId(prdDocumentId);

        if (referenceImage != null && !referenceImage.isEmpty()) {
            applyReferenceImage(request, referenceImage);
        }

        Long userId = getCurrentUserId();
        Long taskId = prototypeGenerateService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    private void applyReferenceImage(PrototypeGenerateRequest request, MultipartFile file) {
        String name = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (!ImageUtils.isSupportedImage(name, contentType)) {
            throw new IllegalArgumentException("参考图仅支持 jpg / png / webp / gif");
        }
        try {
            byte[] raw = file.getBytes();
            ImageUtils.PreparedImage prepared = ImageUtils.prepare(raw, name, contentType);
            String path = fileStorage.store(prepared.bytes(), name != null ? name : "reference.png");

            request.setReferenceImageFileName(name);
            request.setReferenceImagePath(path);
            request.setReferenceImageMimeType(prepared.mimeType());
            request.setReferenceImageBase64(prepared.base64());
            request.setReferenceImageWidth(prepared.width() > 0 ? prepared.width() : null);
            request.setReferenceImageHeight(prepared.height() > 0 ? prepared.height() : null);
            log.info("原型参考图已接收: name={}, mime={}, path={}, b64Len={}",
                    name, prepared.mimeType(), path,
                    prepared.base64() != null ? prepared.base64().length() : 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("参考图处理失败: {}", name, e);
            throw new RuntimeException("参考图处理失败: " + e.getMessage(), e);
        }
    }

    private PrototypeType parsePrototypeType(String value) {
        return parseEnum(PrototypeType.class, value, "prototypeType");
    }

    private Platform parsePlatform(String value) {
        return parseEnum(Platform.class, value, "platform");
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value, String fieldName) {
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("参数 " + fieldName + " 无效: " + value);
        }
    }

    /** 获取原型 HTML 内容（用于 iframe 渲染） */
    @GetMapping("/{id}")
    public ApiResponse<PrototypeResult> getPrototype(@PathVariable Long id) {
        PrototypeResult proto = prototypeResultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("原型不存在"));
        if (!proto.getUserId().equals(getCurrentUserId())) throw new RuntimeException("无权访问该原型");
        return ApiResponse.success(proto);
    }

    /** 保存用户编辑后的原型内容 */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, String>> updatePrototype(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body) {
        PrototypeResult proto = prototypeResultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("原型不存在"));
        if (!proto.getUserId().equals(getCurrentUserId())) throw new RuntimeException("无权访问该原型");

        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank()) throw new IllegalArgumentException("content 不能为空");
        proto.setContent(newContent);
        prototypeResultRepository.save(proto);
        return ApiResponse.success(Map.of("status", "ok"));
    }

    /** 导出原型为 HTML 文件下载 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportHtml(@PathVariable Long id) {
        PrototypeResult proto = prototypeResultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("原型不存在"));
        if (!proto.getUserId().equals(getCurrentUserId())) throw new RuntimeException("无权访问该原型");

        String htmlContent = buildExportHtml(proto);
        String fileName = URLEncoder.encode("原型_" + proto.getId() + ".html", StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .body(htmlContent.getBytes(StandardCharsets.UTF_8));
    }

    private String buildExportHtml(PrototypeResult proto) {
        String content = proto.getContent();
        if (content == null || content.isBlank()) return "<html><body>无内容</body></html>";

        if (proto.getPrototypeType().name().equals("MULTI_PAGE")) {
            try {
                JsonNode pagesNode = objectMapper.readTree(content);
                if (pagesNode.isTextual()) pagesNode = objectMapper.readTree(pagesNode.asText());
                if (pagesNode.isArray() && pagesNode.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
                    sb.append("<meta charset=\"UTF-8\">\n");
                    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
                    sb.append("<title>原型导出 - ").append(proto.getPlatform()).append("</title>\n");
                    sb.append("<style>\n");
                    sb.append("*{margin:0;padding:0;box-sizing:border-box;}\n");
                    sb.append("body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:#f0f2f5;}\n");
                    sb.append(".page-nav{position:fixed;top:0;left:0;width:200px;height:100vh;background:#fff;");
                    sb.append("border-right:1px solid #e8e8e8;overflow-y:auto;padding:16px 12px;z-index:100;}\n");
                    sb.append(".page-nav a{display:block;padding:10px 14px;margin-bottom:6px;border-radius:8px;");
                    sb.append("color:#333;text-decoration:none;font-size:13px;transition:all .2s;}\n");
                    sb.append(".page-nav a:hover,.page-nav a.active{background:#e6f0ff;color:#409eff;}\n");
                    sb.append(".page-content{margin-left:200px;padding:20px;}\n");
                    sb.append(".page-section{background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,0.06);");
                    sb.append("margin-bottom:24px;overflow:hidden;}\n");
                    sb.append(".page-section h2{padding:16px 20px;font-size:16px;border-bottom:1px solid #f0f0f0;}\n");
                    sb.append(".page-section .page-html{padding:0;}\n");
                    sb.append("</style>\n</head>\n<body>\n");
                    sb.append("<nav class=\"page-nav\">\n");
                    for (int i = 0; i < pagesNode.size(); i++) {
                        JsonNode page = pagesNode.get(i);
                        String title = page.has("title") ? page.get("title").asText() : ("页面" + (i + 1));
                        sb.append("  <a href=\"#page-").append(i).append("\">").append(escapeHtml(title)).append("</a>\n");
                    }
                    sb.append("</nav>\n<div class=\"page-content\">\n");
                    for (int i = 0; i < pagesNode.size(); i++) {
                        JsonNode page = pagesNode.get(i);
                        String title = page.has("title") ? page.get("title").asText() : ("页面" + (i + 1));
                        String pageHtml = page.has("html") ? page.get("html").asText() : "";
                        if (pageHtml.startsWith("\"") && pageHtml.endsWith("\"")) {
                            try { pageHtml = objectMapper.readValue(pageHtml, String.class); } catch (Exception ignored) {}
                        }
                        sb.append("<section class=\"page-section\" id=\"page-").append(i).append("\">\n");
                        sb.append("  <h2>").append(escapeHtml(title)).append("</h2>\n");
                        sb.append("  <div class=\"page-html\">\n").append(pageHtml).append("\n  </div>\n");
                        sb.append("</section>\n");
                    }
                    sb.append("</div>\n</body>\n</html>");
                    return sb.toString();
                }
            } catch (Exception ignored) { /* fall through */ }
        }

        String html = unwrapJsonString(content);
        if (!html.trim().startsWith("<!DOCTYPE") && !html.trim().startsWith("<html")) {
            html = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n" +
                   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                   "<title>原型导出</title>\n</head>\n<body>\n" + html + "\n</body>\n</html>";
        }
        return html;
    }

    private String unwrapJsonString(String value) {
        if (value == null) return "";
        String s = value.trim();
        for (int i = 0; i < 5; i++) {
            if (s.startsWith("\"") && s.endsWith("\"")) {
                try {
                    s = objectMapper.readValue(s, String.class);
                    if (s == null) break;
                } catch (Exception e) { break; }
            } else {
                try {
                    JsonNode node = objectMapper.readTree(s);
                    if (node.isTextual()) {
                        s = node.asText();
                        continue;
                    }
                } catch (Exception e) { /* not JSON */ }
                break;
            }
        }
        s = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
        return s;
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
