package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrdReviewFixRequest;
import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;

public interface PrdReviewService {

    /**
     * 提交审查。粘贴模式会自动落库 prd_document，保证报告始终关联有效文档。
     *
     * @return taskId
     */
    Long submit(PrdReviewRequest request, Long userId);

    /**
     * 根据审查报告中的问题，AI 修订 PRD，产出新版 prd_document（不覆盖原版）。
     *
     * @return taskId（完成后 resultRefId = 新 prd_document.id）
     */
    Long submitFix(Long reportId, PrdReviewFixRequest request, Long userId);
}
