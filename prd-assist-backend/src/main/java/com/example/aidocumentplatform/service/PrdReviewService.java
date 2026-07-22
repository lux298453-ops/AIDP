package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrdReviewRequest;

public interface PrdReviewService {
    Long submit(PrdReviewRequest request, Long userId);
}
