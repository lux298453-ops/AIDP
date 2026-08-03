package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.ChartRevisePromptTemplate;
import com.example.aidocumentplatform.common.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.aidocumentplatform.model.dto.request.ChartReviseRequest;
import com.example.aidocumentplatform.model.dto.request.PrdExportRequest;
import com.example.aidocumentplatform.model.dto.request.PrdGenerateRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.enums.DetailLevel;
import com.example.aidocumentplatform.model.enums.TemplateType;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrdGenerateService;
import com.example.aidocumentplatform.util.WordExporter;
import com.example.aidocumentplatform.util.WordReader;
import com.example.aidocumentplatform.util.PrdContentParser;
import com.example.aidocumentplatform.util.XmindParser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PRD 生成接口。
 *
 * POST /api/prd/generate        →  文本输入生成（JSON 或 multipart，multipart 支持 customTemplateFile）
 * POST /api/prd/generate/xmind  →  上传 .xmind 文件自动解析生成
 */
@Slf4j
@RestController
@RequestMapping("/api/prd")
@RequiredArgsConstructor
public class PrdGenerateController {

    private final PrdGenerateService prdGenerateService;
    private final XmindParser xmindParser;
    private final FileStorage fileStorage;
    private final PrdDocumentRepository prdDocumentRepository;
    private final WordExporter wordExporter;
    private final WordReader wordReader;
    private final PrdContentParser prdContentParser;
    private final AiClient aiClient;
    private final ChartRevisePromptTemplate chartRevisePromptTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 文本输入生成（application/json）。
     * 标准模板场景使用；CUSTOM 模板请走 multipart 接口，以便上传文件。
     */
    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Long>> generate(@Valid @RequestBody PrdGenerateRequest request) {
        if (request.getTemplate() == TemplateType.CUSTOM
                && (request.getCustomTemplateContent() == null || request.getCustomTemplateContent().isBlank())) {
            throw new IllegalArgumentException("选择自定义模板时，必须上传模板文件");
        }
        Long userId = getCurrentUserId();
        Long taskId = prdGenerateService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /**
     * 文本输入 + 自定义模板文件生成（multipart/form-data）。
     *
     * 表单字段：
     *   featureName            必填
     *   description            可选
     *   template               STANDARD / CUSTOM，默认 STANDARD
     *   detailLevel            CONCISE / DETAILED，默认 DETAILED
     *   customTemplateFile     template=CUSTOM 时必填，.docx
     */
    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Long>> generateWithTemplate(
            @RequestParam("featureName") String featureName,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "template", defaultValue = "STANDARD") String template,
            @RequestParam(value = "detailLevel", defaultValue = "DETAILED") String detailLevel,
            @RequestParam(value = "customTemplateFile", required = false) MultipartFile customTemplateFile
    ) {
        if (featureName == null || featureName.isBlank()) {
            throw new IllegalArgumentException("功能名不能为空");
        }
        if (featureName.length() > 50) {
            throw new IllegalArgumentException("功能名最多50字");
        }
        PrdGenerateRequest request = new PrdGenerateRequest();
        request.setFeatureName(featureName.trim());
        request.setDescription(description);
        request.setTemplate(parseTemplate(template));
        request.setDetailLevel(parseDetailLevel(detailLevel));

        if (request.getTemplate() == TemplateType.CUSTOM) {
            applyCustomTemplate(request, customTemplateFile);
        }

        Long userId = getCurrentUserId();
        Long taskId = prdGenerateService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /** XMind 文件上传生成 */
    @PostMapping("/generate/xmind")
    public ApiResponse<Map<String, Object>> generateFromXmind(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "template", defaultValue = "STANDARD") String template,
            @RequestParam(value = "detailLevel", defaultValue = "DETAILED") String detailLevel,
            @RequestParam(value = "customTemplateFile", required = false) MultipartFile customTemplateFile
    ) {
        Long userId = getCurrentUserId();

        // 1. 校验文件类型
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".xmind")) {
            throw new RuntimeException("仅支持 .xmind 文件");
        }

        try {
            // 2. 存到文件存储
            byte[] fileBytes = file.getBytes();
            String filePath = fileStorage.store(fileBytes, originalName);

            // 3. 解析 XMind → 结构化大纲
            String outlineText = xmindParser.parse(fileBytes);
            log.info("XMind 解析完成: fileName={}, outlineLength={}", originalName, outlineText.length());

            // 4. 自定义模板（可选）
            String customTemplateContent = null;
            String customTemplateFileName = null;
            if ("CUSTOM".equalsIgnoreCase(template)) {
                if (customTemplateFile == null || customTemplateFile.isEmpty()) {
                    throw new IllegalArgumentException("选择自定义模板时，必须上传模板文件");
                }
                customTemplateContent = extractTemplateText(customTemplateFile);
                customTemplateFileName = customTemplateFile.getOriginalFilename();
            }

            // 5. 提交异步生成
            Long taskId = prdGenerateService.submitXmind(
                    originalName, outlineText, template, detailLevel, userId,
                    customTemplateContent, customTemplateFileName);

            return ApiResponse.success(Map.of(
                    "taskId", taskId,
                    "fileName", originalName,
                    "filePath", filePath,
                    "outline", outlineText
            ));

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("XMind 上传处理失败", e);
            throw new RuntimeException("XMind 处理失败: " + e.getMessage(), e);
        }
    }

    /** 解析并校验自定义模板文件，写入 request。 */
    private void applyCustomTemplate(PrdGenerateRequest request, MultipartFile customTemplateFile) {
        if (customTemplateFile == null || customTemplateFile.isEmpty()) {
            throw new IllegalArgumentException("选择自定义模板时，必须上传模板文件");
        }
        request.setCustomTemplateContent(extractTemplateText(customTemplateFile));
        request.setCustomTemplateFileName(customTemplateFile.getOriginalFilename());
    }

    /** 从 .docx 提取模板纯文本，并落盘存档。 */
    private String extractTemplateText(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("自定义模板仅支持 .docx 文件");
        }
        try {
            byte[] bytes = file.getBytes();
            fileStorage.store(bytes, name);
            String text = wordReader.extractText(bytes);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("自定义模板内容为空，请检查文件");
            }
            log.info("自定义模板解析完成: fileName={}, length={}", name, text.length());
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("自定义模板解析失败: {}", name, e);
            throw new RuntimeException("自定义模板解析失败: " + e.getMessage(), e);
        }
    }

    private TemplateType parseTemplate(String value) {
        try {
            return TemplateType.valueOf(value);
        } catch (Exception e) {
            return TemplateType.STANDARD;
        }
    }

    private DetailLevel parseDetailLevel(String value) {
        try {
            return DetailLevel.valueOf(value);
        } catch (Exception e) {
            return DetailLevel.DETAILED;
        }
    }

    /** 获取 PRD 文档内容（content 返回解析后的 JSON 对象而非字符串） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getPrd(@PathVariable Long id) {
        PrdDocument prd = getOwnedPrd(id);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", prd.getId());
        result.put("userId", prd.getUserId());
        result.put("taskId", prd.getTaskId());
        result.put("title", prd.getTitle());
        result.put("description", prd.getDescription());
        result.put("sourceType", prd.getSourceType().name());
        result.put("template", prd.getTemplate().name());
        result.put("detailLevel", prd.getDetailLevel().name());
        result.put("createdAt", prd.getCreatedAt());
        // content 字段解析为 JSON 对象返回
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode node = om.readTree(prd.getContent());
            result.put("content", om.convertValue(node, Map.class));
        } catch (Exception e) {
            result.put("content", prd.getContent());
        }
        return ApiResponse.success(result);
    }

    /** 保存用户在结果页编辑后的结构化 PRD。 */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updatePrd(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        PrdDocument prd = getOwnedPrd(id);
        Object content = body.get("content");
        if (content == null) throw new IllegalArgumentException("content 不能为空");
        try {
            String raw = content instanceof String value ? value : new ObjectMapper().writeValueAsString(content);
            prd.setContent(prdContentParser.normalizeToJson(raw));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("PRD 内容格式错误", e);
        }
        Object title = body.get("title");
        if (title != null && !title.toString().isBlank()) {
            if (title.toString().length() > 50) throw new IllegalArgumentException("标题最多 50 字");
            prd.setTitle(title.toString().trim());
        }
        Object description = body.get("description");
        if (description != null) prd.setDescription(description.toString());
        prdDocumentRepository.save(prd);
        return getPrd(id);
    }

    /** 导出 PRD 为 Word 文档（CUSTOM 按 chapters 原样导出，STANDARD 走公司固定骨架） */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportWord(@PathVariable Long id) {
        PrdDocument prd = getOwnedPrd(id);
        byte[] docBytes = wordExporter.export(
                prd.getTitle(), prd.getDescription(), prd.getContent(), prd.getTemplate());
        return buildDocxResponse(prd.getTitle(), docBytes);
    }

    /**
     * 带图表图片导出：前端将图表渲染为 PNG base64 后 POST。
     * body.chartImages[].key = 章节下标（"0"）或 "summary"
     */
    @PostMapping("/{id}/export")
    public ResponseEntity<byte[]> exportWordWithCharts(
            @PathVariable Long id,
            @RequestBody(required = false) PrdExportRequest request) {
        PrdDocument prd = getOwnedPrd(id);
        Map<String, byte[]> images = new HashMap<>();
        if (request != null && request.getChartImages() != null) {
            for (PrdExportRequest.ChartImage img : request.getChartImages()) {
                if (img == null || img.getKey() == null || img.getPngBase64() == null) continue;
                try {
                    String b64 = img.getPngBase64().trim();
                    int comma = b64.indexOf(',');
                    if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
                    images.put(img.getKey(), Base64.getDecoder().decode(b64));
                } catch (Exception e) {
                    log.warn("解析图表 PNG 失败: key={}", img.getKey(), e);
                }
            }
        }
        byte[] docBytes = wordExporter.export(
                prd.getTitle(), prd.getDescription(), prd.getContent(), prd.getTemplate(), images);
        return buildDocxResponse(prd.getTitle(), docBytes);
    }

    /**
     * AI 修订某章节中的图表（PlantUML / Mermaid）。
     * 同步返回新图表源码，并写回 prd_document.content（保留原说明文字）。
     */
    @PostMapping("/{id}/chart-revise")
    public ApiResponse<Map<String, Object>> reviseChart(
            @PathVariable Long id,
            @Valid @RequestBody ChartReviseRequest request) {
        PrdDocument prd = getOwnedPrd(id);
        int idx = request.getChapterIndex();
        try {
            JsonNode root = objectMapper.readTree(prd.getContent());
            JsonNode chaptersNode = root.path("chapters");
            if (!chaptersNode.isArray() || idx < 0 || idx >= chaptersNode.size()) {
                throw new IllegalArgumentException("章节下标无效");
            }
            ObjectNode chapter = (ObjectNode) chaptersNode.get(idx);
            String title = chapter.path("title").asText("");
            String type = chapter.path("type").asText("chart");
            String oldContent = chapter.path("content").asText("");
            String currentCode = request.getCurrentCode();
            if (currentCode == null || currentCode.isBlank()) {
                currentCode = extractChart(oldContent);
            }
            if (currentCode == null || currentCode.isBlank()) {
                throw new IllegalArgumentException("该章节未找到图表源码（PlantUML/Mermaid）");
            }
            String lang = detectChartLang(currentCode);

            String system = chartRevisePromptTemplate.getSystemPrompt();
            String user = chartRevisePromptTemplate.buildUserPrompt(
                    type, title, currentCode, request.getInstruction(), lang);
            String aiRaw;
            AiRequestContext.setUserId(getCurrentUserId());
            try {
                aiRaw = aiClient.generate(system, user);
            } finally {
                AiRequestContext.clear();
            }
            String newCode = stripChartFence(aiRaw, lang);
            if (newCode.isBlank()) {
                throw new IllegalArgumentException("AI 未返回有效图表源码");
            }

            String newContent = replaceChart(oldContent, newCode, lang);
            chapter.put("content", newContent);

            // 写回完整 JSON
            ((ObjectNode) root).set("chapters", chaptersNode);
            prd.setContent(objectMapper.writeValueAsString(root));
            prdDocumentRepository.save(prd);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", newCode);
            result.put("content", newContent);
            result.put("chapterIndex", idx);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI 修订图表失败: prdId={}, chapter={}", id, idx, e);
            throw new RuntimeException("AI 修订图表失败: " + e.getMessage(), e);
        }
    }

    private ResponseEntity<byte[]> buildDocxResponse(String title, byte[] docBytes) {
        String fileName = URLEncoder.encode((title != null ? title : "PRD") + ".docx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .body(docBytes);
    }

    private static final Pattern MERMAID_FENCE = Pattern.compile(
            "```mermaid[ \\t]*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final Pattern PLANTUML_FENCE = Pattern.compile(
            "```plantuml[ \\t]*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAW_PLANTUML = Pattern.compile(
            "(?s)@startuml[\\s\\S]*?@enduml");

    /** 提取章节中的图表源码（优先 PlantUML，其次 Mermaid） */
    private String extractChart(String content) {
        if (content == null) return null;
        String text = content.replace("\\n", "\n");
        Matcher p = PLANTUML_FENCE.matcher(text);
        if (p.find()) return p.group(1).trim();
        Matcher rp = RAW_PLANTUML.matcher(text);
        if (rp.find()) return rp.group(0).trim();
        Matcher m = MERMAID_FENCE.matcher(text);
        if (m.find()) return m.group(1).trim();
        // 裸 flowchart
        for (String line : text.split("\n")) {
            if (line.trim().matches("(?i)^(flowchart|graph|sequenceDiagram)\\b.*")) {
                return text.substring(text.indexOf(line)).trim();
            }
        }
        return null;
    }

    private String detectChartLang(String code) {
        if (code != null && (code.contains("@startuml") || code.contains("@enduml"))) {
            return "plantuml";
        }
        return "mermaid";
    }

    private String stripChartFence(String raw, String lang) {
        if (raw == null) return "";
        String s = raw.trim();
        boolean plantuml = "plantuml".equalsIgnoreCase(lang);
        // 去掉可能的 markdown 围栏
        s = s.replaceAll("(?is)^```(?:plantuml|mermaid)?\\s*", "").replaceAll("(?is)```\\s*$", "").trim();
        if (plantuml) {
            // 只保留 @startuml ... @enduml 块
            Matcher rp = RAW_PLANTUML.matcher(s);
            if (rp.find()) s = rp.group(0).trim();
        }
        // 若 AI 仍返回了 JSON，尝试取 code 字段
        if (s.startsWith("{")) {
            try {
                JsonNode n = objectMapper.readTree(s);
                if (n.has("code")) return n.get("code").asText("").trim();
                if (n.has("chart")) return n.get("chart").asText("").trim();
            } catch (Exception ignored) { /* use raw */ }
        }
        return s;
    }

    private String replaceChart(String content, String newCode, String lang) {
        String text = content == null ? "" : content.replace("\\n", "\n");
        boolean plantuml = "plantuml".equalsIgnoreCase(lang);
        String block;
        if (plantuml) {
            block = "```plantuml\n" + newCode.trim() + "\n```";
        } else {
            block = "```mermaid\n" + newCode.trim() + "\n```";
        }
        Matcher m = (plantuml ? PLANTUML_FENCE : MERMAID_FENCE).matcher(text);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(block));
        }
        // 无围栏：前置新图，保留原文作为说明
        if (text.isBlank()) return block;
        return block + "\n\n" + text;
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }

    private PrdDocument getOwnedPrd(Long id) {
        PrdDocument prd = prdDocumentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("PRD不存在"));
        if (!prd.getUserId().equals(getCurrentUserId())) throw new RuntimeException("无权访问该 PRD");
        return prd;
    }
}
