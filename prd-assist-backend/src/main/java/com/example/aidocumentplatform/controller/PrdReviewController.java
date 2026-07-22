package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrdReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PRD 审查接口。
 *
 * POST /api/prd/review    →  提交审查，返回 { taskId }
 * GET  /api/review/{id}   →  获取审查报告
 */
@RestController
@RequiredArgsConstructor
public class PrdReviewController {

    private final PrdReviewService prdReviewService;
    private final ReviewReportRepository reviewReportRepository;

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
        return ApiResponse.success(report);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
