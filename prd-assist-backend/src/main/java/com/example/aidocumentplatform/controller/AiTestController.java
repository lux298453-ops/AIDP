package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 客户端测试接口 —— 用于验证 Claude API 调用链路是否通畅。
 *
 * 测试方式:
 *   POST /api/ai/test
 *   Body: { "systemPrompt": "你是一个助手", "userPrompt": "你好" }
 *
 * 该接口是临时测试用途，验证通过后可删除。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiTestController {

    private final AiClient aiClient;

    /**
     * 测试 AI 调用链路。
     *
     * @param body 包含 systemPrompt 和 userPrompt
     * @return AI 原始返回的 JSON 字符串
     */
    @PostMapping("/test")
    public ApiResponse<String> testAi(@RequestBody Map<String, String> body) {
        String systemPrompt = body.getOrDefault("systemPrompt", "你是一个乐于助人的助手。");
        String userPrompt = body.getOrDefault("userPrompt", "请用一句话介绍你自己。");

        String rawJson;
        AiRequestContext.setUserId(getCurrentUserId());
        try {
            rawJson = aiClient.generate(systemPrompt, userPrompt);
        } finally {
            AiRequestContext.clear();
        }

        return ApiResponse.success(rawJson);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
