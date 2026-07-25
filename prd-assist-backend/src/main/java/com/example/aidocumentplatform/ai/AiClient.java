package com.example.aidocumentplatform.ai;

/**
 * AI 大模型客户端统一接口。
 *
 * 所有 AI 厂商的实现（Claude、GPT、文心等）都应实现此接口，
 * 方便业务层通过接口编程，不绑定具体厂商。
 */
public interface AiClient {

    /**
     * 向大模型发送请求并获取回复。
     *
     * @param systemPrompt 系统提示词（设定 AI 的角色和行为规则）
     * @param userPrompt   用户提示词（具体的问题或任务描述）
     * @return AI 返回的文本内容（JSON 原始字符串，由调用方解析）
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * 带图片的多模态生成（风格参考图等）。
     * 默认实现：忽略图片，退化为纯文本（适合 DeepSeek 等无 vision 模型）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词（可含风格说明）
     * @param imageMime    图片 MIME，如 image/png；可为 null
     * @param imageBase64  图片 base64（无 data: 前缀）；可为 null
     */
    default String generateWithImage(String systemPrompt, String userPrompt,
                                     String imageMime, String imageBase64) {
        return generate(systemPrompt, userPrompt);
    }
}
