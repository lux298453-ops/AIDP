package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrdEnhanceRequest;

/**
 * PRD 增强服务。
 */
public interface PrdEnhanceService {

    /**
     * 提交增强请求 → async_task(PENDING) → 返回 taskId。
     */
    Long submit(PrdEnhanceRequest request, Long userId);

    /**
     * 提交 Word 文件增强请求（multipart 上传）。
     */
    Long submitWithWord(PrdEnhanceRequest request, byte[] wordBytes, String fileName, Long userId);
}
