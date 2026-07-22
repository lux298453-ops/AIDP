package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;

/**
 * 原型生成服务。
 */
public interface PrototypeGenerateService {

    /**
     * 提交原型生成请求 → async_task(PENDING) → 返回 taskId。
     */
    Long submit(PrototypeGenerateRequest request, Long userId);
}
