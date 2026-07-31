package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.PrdReviewFixRequest;
import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrdReviewService;
import com.example.aidocumentplatform.util.ReviewWordExporter;
import com.example.aidocumentplatform.util.WordExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PRD 审查接口。
 *
 * POST /api/prd/review                  → 提交审查，返回 { taskId }
 * GET  /api/review/{id}                 → 获取审查报告（含 prdDocumentId）
 * GET  /api/review/{id}/export          → 导出审查报告 Word
 * GET  /api/review/{id}/export-prd      → 导出关联 PRD Word
 * POST /api/review/{id}/fix             → AI 按问题修订 PRD，返回 { taskId }
 * POST /api/review/{id}/fix-inline      → AI 内联精准修复单条问题，返回 { prdDocumentId, oldText, newText, changeSummary }
 */
@RestController
@RequiredArgsConstructor
public class PrdReviewController {

    private final PrdReviewService prdReviewService;
    private final ReviewReportRepository reviewReportRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final ReviewWordExporter reviewWordExporter;
    private final WordExporter wordExporter;

    @PostMapping("/api/prd/review")
    public ApiResponse<Map<String, Long>> review(@RequestBody PrdReviewRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prdReviewService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    @GetMapping("/api/review/{id}")
    public ApiResponse<Map<String, Object>> getReport(@PathVariable Long id) {
        ReviewReport report = getOwnedReport(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", report.getId());
        result.put("userId", report.getUserId());
        result.put("prdDocumentId", report.getPrdDocumentId());
        result.put("taskId", report.getTaskId());
        result.put("dimensions", report.getDimensions());
        result.put("issues", report.getIssues());
        result.put("createdAt", report.getCreatedAt());
        return ApiResponse.success(result);
    }

    /** 导出审查报告为 Word */
    @GetMapping("/api/review/{id}/export")
    public ResponseEntity<byte[]> exportWord(@PathVariable Long id) {
        ReviewReport report = getOwnedReport(id);
        byte[] docBytes = reviewWordExporter.export(report.getIssues(), report.getDimensions());
        String fileName = URLEncoder.encode("审查报告_" + report.getId() + ".docx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .body(docBytes);
    }

    /** 导出关联的 PRD 文档为 Word（修后/原版均可，按 prdDocumentId） */
    @GetMapping("/api/review/{id}/export-prd")
    public ResponseEntity<byte[]> exportLinkedPrd(@PathVariable Long id) {
        ReviewReport report = getOwnedReport(id);
        PrdDocument prd = getLinkedPrd(report);
        byte[] docBytes = wordExporter.export(
                prd.getTitle(), prd.getDescription(), prd.getContent(), prd.getTemplate());
        String fileName = URLEncoder.encode(prd.getTitle() + ".docx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                .body(docBytes);
    }

    /**
     * AI 按审查问题修订 PRD。
     * 默认修复 CRITICAL + MAJOR；可传 issueIndexes 或 severities。
     * 完成后 task.resultRefId = 新 prd_document.id（不覆盖原版）。
     */
    @PostMapping("/api/review/{id}/fix")
    public ApiResponse<Map<String, Long>> fix(
            @PathVariable Long id,
            @RequestBody(required = false) PrdReviewFixRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prdReviewService.submitFix(
                id, request != null ? request : new PrdReviewFixRequest(), userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /**
     * 内联精准修复：AI 只返回 oldText → newText 的文本替换。
     * 同步返回结果，供前端原地更新文本框并高亮修改文字。
     */
    @PostMapping("/api/review/{id}/fix-inline")
    public ApiResponse<Map<String, Object>> fixInline(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        int issueIndex = body.containsKey("issueIndex") ? ((Number) body.get("issueIndex")).intValue() : 0;
        Long sourcePrdDocumentId = body.containsKey("sourcePrdDocumentId")
                ? ((Number) body.get("sourcePrdDocumentId")).longValue() : null;
        Map<String, Object> result = prdReviewService.submitInlineFix(id, issueIndex, sourcePrdDocumentId, userId);
        return ApiResponse.success(result);
    }

    private ReviewReport getOwnedReport(Long id) {
        ReviewReport report = reviewReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("审查报告不存在"));
        if (!report.getUserId().equals(getCurrentUserId())) {
            throw new IllegalArgumentException("无权访问该审查报告");
        }
        return report;
    }

    private PrdDocument getLinkedPrd(ReviewReport report) {
        if (report.getPrdDocumentId() == null || report.getPrdDocumentId() <= 0) {
            throw new IllegalArgumentException("该报告未关联 PRD 文档");
        }
        PrdDocument prd = prdDocumentRepository.findById(report.getPrdDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("关联的 PRD 不存在"));
        if (!prd.getUserId().equals(getCurrentUserId())) {
            throw new IllegalArgumentException("无权访问该 PRD");
        }
        return prd;
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
