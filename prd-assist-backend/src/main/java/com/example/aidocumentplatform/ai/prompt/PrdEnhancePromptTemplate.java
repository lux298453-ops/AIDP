package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PRD 增强的 Prompt 模板。
 *
 * 根据用户选择的内容类型生成对应的补充章节：
 *   structure → 页面结构图
 *   flow      → 流程图
 *   data      → 数据字段
 *   testcase  → 测试用例
 */
@Component
public class PrdEnhancePromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是一位资深产品策划专家，擅长基于已有 PRD 文档进行内容增强和补充。
            你的输出必须严格遵循指定格式，确保内容清晰、可执行、可直接交付。
            请始终以中文输出，专业术语可保留英文。
            """;

    /**
     * 构建 user prompt。
     *
     * @param prdContent   原始 PRD 文本
     * @param wordContent  Word 素材文本（可为 null）
     * @param contentTypes 用户选择的内容类型
     * @param instruction  用户自定义指令（可为 null）
     */
    public String buildUserPrompt(String prdContent, String wordContent,
                                   List<String> contentTypes, String instruction) {
        StringBuilder sections = new StringBuilder();
        for (String type : contentTypes) {
            sections.append("  - ").append(getTypeLabel(type)).append(": ").append(getTypeDesc(type)).append("\n");
        }

        StringBuilder extraInfo = new StringBuilder();
        if (wordContent != null && !wordContent.isBlank()) {
            extraInfo.append("【参考素材（Word文档）】\n").append(wordContent).append("\n\n");
        }
        if (instruction != null && !instruction.isBlank()) {
            extraInfo.append("【自定义增强指令】\n").append(instruction).append("\n");
        }

        return """
                请基于以下原始 PRD 内容，补充指定的章节，以严格的 JSON 格式输出。

                【原始 PRD】
                %s

                %s
                【需要补充的章节】
                %s
                【输出格式】
                你必须返回一个 JSON 对象（不要包含 markdown 代码块标记），结构如下：
                {
                  "sections": [
                    {
                      "type": "structure|flow|data|testcase",
                      "title": "章节标题",
                      "content": "详细补充内容（Markdown 格式）"
                    }
                  ]
                }

                请严格按 JSON 格式返回，不要添加任何解释文字。
                """.formatted(prdContent, extraInfo.toString(), sections.toString());
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private String getTypeLabel(String type) {
        return switch (type) {
            case "structure" -> "页面结构图";
            case "flow" -> "流程图";
            case "data" -> "数据字段";
            case "testcase" -> "测试用例";
            default -> type;
        };
    }

    private String getTypeDesc(String type) {
        return switch (type) {
            case "structure" -> "页面的层级结构、导航关系、路由设计";
            case "flow" -> "核心业务的流程图描述（可用 Mermaid 语法）";
            case "data" -> "关键数据字段定义、类型、校验规则、默认值";
            case "testcase" -> "核心功能的测试用例（输入、预期输出、边界条件）";
            default -> "补充内容";
        };
    }
}
