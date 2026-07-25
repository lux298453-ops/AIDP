package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrototypeAiEditRequest;
import com.example.aidocumentplatform.model.dto.response.PrototypeAiEditResponse;

/**
 * 原型 AI 辅助修改服务。
 * 根据用户的自然语言指令修改现有原型 HTML，并返回修改结果。
 */
public interface PrototypeAiEditService {

    /**
     * 对指定原型执行 AI 修改。
     *
     * @param prototypeId 原型 ID
     * @param userId      当前登录用户 ID（用于鉴权）
     * @param request     修改请求（指令、可选目标元素、可选最新 HTML）
     * @return 修改后的 HTML 与改动说明
     */
    PrototypeAiEditResponse edit(Long prototypeId, Long userId, PrototypeAiEditRequest request);
}
