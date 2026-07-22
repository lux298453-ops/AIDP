package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.common.FileStorage;
import com.example.aidocumentplatform.model.dto.request.PrdGenerateRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrdGenerateService;
import com.example.aidocumentplatform.util.WordExporter;
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
import java.util.Map;

/**
 * PRD 生成接口。
 *
 * POST /api/prd/generate        →  文本输入生成，返回 { taskId }
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

    /** 文本输入生成 */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Long>> generate(@Valid @RequestBody PrdGenerateRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prdGenerateService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /** XMind 文件上传生成 */
    @PostMapping("/generate/xmind")
    public ApiResponse<Map<String, Object>> generateFromXmind(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "template", defaultValue = "STANDARD") String template,
            @RequestParam(value = "detailLevel", defaultValue = "DETAILED") String detailLevel
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

            // 4. 提交异步生成
            Long taskId = prdGenerateService.submitXmind(originalName, outlineText, template, detailLevel, userId);

            return ApiResponse.success(Map.of(
                    "taskId", taskId,
                    "fileName", originalName,
                    "filePath", filePath,
                    "outline", outlineText
            ));

        } catch (Exception e) {
            log.error("XMind 上传处理失败", e);
            throw new RuntimeException("XMind 处理失败: " + e.getMessage(), e);
        }
    }

    /** 获取 PRD 文档内容 */
    @GetMapping("/{id}")
    public ApiResponse<PrdDocument> getPrd(@PathVariable Long id) {
        PrdDocument prd = prdDocumentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("PRD不存在"));
        return ApiResponse.success(prd);
    }

    /** 导出 PRD 为 Word 文档 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportWord(@PathVariable Long id) {
        PrdDocument prd = prdDocumentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("PRD不存在"));
        byte[] docBytes = wordExporter.export(prd.getTitle(), prd.getDescription(), prd.getContent());
        String fileName = URLEncoder.encode(prd.getTitle() + ".docx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .body(docBytes);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
