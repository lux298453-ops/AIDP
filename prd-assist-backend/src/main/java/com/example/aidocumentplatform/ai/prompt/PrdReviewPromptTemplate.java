package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PRD 审查的 Prompt 模板。
 *
 * 按三个维度审查：完整性 (completeness)、一致性 (consistency)、合规性 (compliance)，
 * 输出结构化问题列表 [{severity, location, description, suggestion}]。
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
    public String buildUserPrompt(String prdContent, List<String> dimensions, String requirement) {
        StringBuilder dimDesc = new StringBuilder();
        if (dimensions != null) {
            for (String d : dimensions) dimDesc.append("  - ").append(getDimensionDesc(d)).append("\n");
        }
        String reqText = (requirement != null && !requirement.isBlank()) ? "【额外要求】" + requirement + "\n" : "";

        return """
                请对以下 PRD 文档进行专业审查，以严格的 JSON 格式输出问题列表。

                【PRD 文档】
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
                      "location": "问题所在的具体章节或段落",
                      "description": "问题的具体描述",
                      "suggestion": "修改建议"
                    }
                  ]
                }

                【严重程度说明】
                - CRITICAL: 阻塞性问题，必须修复才能进入开发
                - MAJOR: 重要问题，强烈建议修复
                - MINOR: 小问题，可后续迭代修复
                - SUGGESTION: 优化建议

                请严格按 JSON 格式返回，不要添加任何解释文字。
                """.formatted(prdContent, dimDesc.toString(), reqText);
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
