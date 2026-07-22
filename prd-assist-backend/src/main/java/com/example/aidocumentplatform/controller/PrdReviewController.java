package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrdReviewService;
import com.example.aidocumentplatform.util.ReviewWordExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * PRD 审查接口。
 *
 * POST /api/prd/review      →  提交审查，返回 { taskId }
 * GET  /api/review/{id}     →  获取审查报告
 * GET  /api/review/{id}/export → 导出审查报告为 Word 文档
 */
@RestController
@RequiredArgsConstructor
public class PrdReviewController {

    private final PrdReviewService prdReviewService;
    private final ReviewReportRepository reviewReportRepository;
    private final ReviewWordExporter reviewWordExporter;

    @PostMapping("/api/prd/review")
    public ApiResponse<Map<String, Long>> review(@RequestBody PrdReviewRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prdReviewService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    @GetMapping("/api/review/{id}")
    public ApiResponse<ReviewReport> getReport(@PathVariable Long id) {
        ReviewReport report = reviewReportRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("审查报告不存在"));
        if (!report.getUserId().equals(getCurrentUserId())) throw new RuntimeException("无权访问该审查报告");
        return ApiResponse.success(report);
    }

    /** 导出审查报告为 Word 文档 */
    @GetMapping("/api/review/{id}/export")
    public ResponseEntity<byte[]> exportWord(@PathVariable Long id) {
        ReviewReport report = reviewReportRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("审查报告不存在"));
        if (!report.getUserId().equals(getCurrentUserId())) throw new RuntimeException("无权访问该审查报告");

        byte[] docBytes = reviewWordExporter.export(report.getIssues(), report.getDimensions());
        String fileName = URLEncoder.encode("审查报告_" + report.getId() + ".docx", StandardCharsets.UTF_8)
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
