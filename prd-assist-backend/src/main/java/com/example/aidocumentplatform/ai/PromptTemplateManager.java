package com.example.aidocumentplatform.ai;

import org.springframework.stereotype.Component;

/**
 * Prompt 模板管理器。
 *
 * 为每种业务场景提供结构化的 system prompt + user prompt 对，
 * 由调用方通过 AiClient.generate(systemPrompt, userPrompt) 发送给大模型。
 */
@Component
public class PromptTemplateManager {

    /**
     * 获取 PRD 生成场景的 system prompt。
     */
    public String getSystemPrompt() {
        return "你是一位资深的游戏策划文档专家。请以专业、清晰的语言输出结构化的 PRD 文档，使用 Markdown 格式。";
    }

    // ==================== PRD 生成 ====================

    public String getGeneratePrdUserPrompt(String inputData) {
        return """
                根据以下输入信息，生成一份结构化的 PRD（产品需求文档）。

                要求：
                1. 包含功能概述、核心玩法、UI/UX说明、技术需求等标准章节
                2. 语言专业、逻辑清晰、可直接交付
                3. 以 Markdown 格式输出

                输入信息：
                %s
                """.formatted(inputData);
    }

    // ==================== PRD 增强 ====================

    public String getEnhancePrdUserPrompt(String prdContent, String instruction) {
        return """
                请根据以下指令对现有 PRD 文档进行增强和完善。

                增强指令：%s

                原始 PRD：
                %s

                请输出增强后的完整 PRD（Markdown 格式）。
                """.formatted(instruction, prdContent);
    }

    // ==================== PRD 审查 ====================

    public String getReviewPrdUserPrompt(String prdContent) {
        return """
                请对以下 PRD 文档进行全面评审。

                评审维度：
                1. 功能完整性 — 是否有遗漏的关键功能或边界情况
                2. 逻辑一致性 — 各模块之间是否存在矛盾
                3. 可实现性 — 技术实现上是否有明显的难点或风险
                4. 用户体验 — 是否有更好的交互设计建议
                5. 文档质量 — 表述是否清晰、是否有歧义

                请以评审报告形式输出（Markdown 格式），包含问题列表和修改建议。

                PRD 文档：
                %s
                """.formatted(prdContent);
    }

    // ==================== 原型生成 ====================

    public String getGeneratePrototypeUserPrompt(String prdContent) {
        return """
                根据以下 PRD 文档，生成一个可交互的 HTML 原型页面。

                要求：
                1. 使用纯 HTML+CSS+JS，单文件，可直接在浏览器中打开
                2. 包含完整的 UI 布局和交互逻辑
                3. 使用现代设计风格（参考 Element Plus 风格）
                4. 包含必要的模拟数据，能体现核心交互流程
                5. 代码结构清晰，有注释

                PRD 文档：
                %s

                请只输出 HTML 代码（包含 style 和 script），不要有任何解释文字。
                """.formatted(prdContent);
    }
}
