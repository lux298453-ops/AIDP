package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PRD 审查的 Prompt 模板。
 *
 * 按三个维度审查：完整性 (completeness)、一致性 (consistency)、合规性 (compliance)，
 * 输出结构化问题列表 [{severity, location, chapterIndex, chapterTitle, description, suggestion}]。
 */
@Component
public class PrdReviewPromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是一位资深产品评审专家，擅长发现 PRD 文档中的缺陷和风险。
            你的评审意见必须具体、可操作，每条问题都要指明位置并给出修改建议。
            请始终以中文输出。
            """;

    /**
     * 构建 user prompt。
     */
    public String buildUserPrompt(String prdContent, List<String> dimensions, String requirement, String chapterOutline) {
        StringBuilder dimDesc = new StringBuilder();
        if (dimensions != null) {
            for (String d : dimensions) dimDesc.append("  - ").append(getDimensionDesc(d)).append("\n");
        }
        String reqText = (requirement != null && !requirement.isBlank()) ? "【额外要求】" + requirement + "\n" : "";
        String outlineText = (chapterOutline != null && !chapterOutline.isBlank())
                ? chapterOutline
                : "（未识别到结构化章节，请将 chapterIndex 置为 -1，chapterTitle 置为空字符串）";

        return """
                请对以下 PRD 文档进行专业审查，以严格的 JSON 格式输出问题列表。

                【PRD 文档】
                %s

                【可定位章节大纲】
                %s

                【审查维度】
                %s
                %s
                 【输出格式】
                 你必须返回一个 JSON 对象（不要包含 markdown 代码块标记）：
                 {
                   "summary": "总体评价（一段话）",
                   "score": 85,
                   "issues": [
                     {
                       "severity": "CRITICAL|MAJOR|MINOR|SUGGESTION",
                       "dimension": "completeness|consistency|compliance",
                       "chapterIndex": 0,
                       "chapterTitle": "与 chapterIndex 对应的章节标题",
                       "location": "问题所在的具体章节或段落",
                       "targetText": "从 PRD 原文中摘录的问题相关原文片段（20~200 字），用于前端精准定位和高亮。如果问题是整体性的（如缺少某章节），填 ''",
                       "description": "问题的具体描述",
                       "suggestion": "修改建议"
                     }
                   ]
                 }

                 【targetText 要求】
                 - targetText 必须是从 PRD 原文中逐字摘录的片段，不能自己概括或改写
                 - 摘录长度 20~200 字，要足够唯一以便前端在章节中精准定位
                 - 如果问题是"缺少某内容"或"整体结构问题"，targetText 填空字符串 ""
                 - 如果问题涉及多个分散的句子，摘录最核心的一句


                【严重程度说明】
                - CRITICAL: 阻塞性问题，必须修复才能进入开发
                - MAJOR: 重要问题，强烈建议修复
                - MINOR: 小问题，可后续迭代修复
                - SUGGESTION: 优化建议

                【定位要求】
                - chapterIndex 必须使用上方章节大纲中的 0-based 索引；如果问题属于整体文档或无法归属到具体章节，填 -1
                - chapterTitle 必须与 chapterIndex 对应的章节标题完全一致；chapterIndex 为 -1 时填空字符串
                - location 可以写更细的段落/模块名，但不要替代 chapterIndex

                请严格按 JSON 格式返回，不要添加任何解释文字。
                """.formatted(prdContent, outlineText, dimDesc.toString(), reqText);
    }

    public String getSystemPrompt() { return SYSTEM_PROMPT; }

    private String getDimensionDesc(String d) {
        return switch (d) {
            case "completeness" -> "完整性：功能是否遗漏、边界是否覆盖、异常场景是否考虑";
            case "consistency" -> "一致性：各模块之间逻辑是否矛盾、术语是否统一、格式是否规范";
            case "compliance" -> "合规性：是否符合行业规范、安全要求、数据隐私标准";
            default -> d;
        };
    }
}
