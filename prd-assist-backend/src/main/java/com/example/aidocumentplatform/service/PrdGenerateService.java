package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrdGenerateRequest;

/**
 * PRD 生成服务 —— 将请求提交为异步任务，后台生成后存入 prd_document。
 */
public interface PrdGenerateService {

    /**
     * 提交生成请求 → 创建 async_task(PENDING) → 触发异步生成。
     *
     * @param request 生成参数
     * @param userId  当前用户 ID
     * @return async_task.id（taskId），前端用此 ID 轮询/SSE 获取进度
     */
    Long submit(PrdGenerateRequest request, Long userId);

    /**
     * 提交 XMind 文件生成请求。
     *
     * @param fileName    原始文件名
     * @param outlineText XMind 解析后的大纲文本（作为 description 传给 AI）
     * @param template    模板类型
     * @param detailLevel 详略程度
     * @param userId      当前用户 ID
     * @return async_task.id（taskId）
     */
    Long submitXmind(String fileName, String outlineText, String template, String detailLevel, Long userId);

    /**
     * 提交 XMind 文件生成请求（支持自定义模板）。
     *
     * @param customTemplateContent  自定义模板纯文本，template=CUSTOM 时必填
     * @param customTemplateFileName 自定义模板文件名
     */
    Long submitXmind(String fileName, String outlineText, String template, String detailLevel, Long userId,
                     String customTemplateContent, String customTemplateFileName);
}
