package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PRD 增强的 Prompt 模板。
 *
 * 根据用户选择的内容类型生成对应的补充章节：
 *   structure → 页面结构图（必须含 Mermaid 图）
 *   flow      → 流程图（必须含 Mermaid 图）
 *   data      → 数据字段
 *   testcase  → 测试用例
 */
@Component
public class PrdEnhancePromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是一位资深产品策划与信息架构专家，擅长基于已有 PRD 补充可交付的增强章节。
            当用户要求页面结构图或流程图时，你必须输出可直接渲染的 Mermaid 图表源码，
            而不是只写文字描述或「见下图」占位。
            输出必须是合法 JSON（不要包在 markdown 代码块外层），content 字段内可以包含 Mermaid 围栏。
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
        List<String> types = contentTypes != null ? contentTypes : List.of();
        boolean needStructure = types.stream().anyMatch(t -> "structure".equalsIgnoreCase(t));
        boolean needFlow = types.stream().anyMatch(t -> "flow".equalsIgnoreCase(t));

        StringBuilder sections = new StringBuilder();
        for (String type : types) {
            sections.append("  - ").append(getTypeLabel(type)).append(": ").append(getTypeDesc(type)).append("\n");
        }

        StringBuilder extraInfo = new StringBuilder();
        if (wordContent != null && !wordContent.isBlank()) {
            extraInfo.append("【参考素材（Word文档）】\n").append(wordContent).append("\n\n");
        }
        if (instruction != null && !instruction.isBlank()) {
            extraInfo.append("【自定义增强指令】\n").append(instruction).append("\n");
        }

        StringBuilder chartRules = new StringBuilder();
        if (needStructure || needFlow) {
            chartRules.append("""

                【图表强制要求 — 违反即失败】
                """);
            if (needStructure) {
                chartRules.append("""
                        - type=structure 的 content 中必须包含至少一个 Mermaid 代码块，格式严格为：
                          ```mermaid
                          flowchart TD
                            A[根节点] --> B[子页面]
                            B --> C[详情页]
                          ```
                        - 图中体现：页面层级、导航入口、主要子页面/弹窗；节点名用中文，至少 6 个节点
                        - 代码块前后可附文字说明（页面清单、路由建议），但图表本身不可省略
                        """);
            }
            if (needFlow) {
                chartRules.append("""
                        - type=flow 的 content 中必须包含至少一个 Mermaid 代码块，格式严格为：
                          ```mermaid
                          flowchart TD
                            Start([开始]) --> Step1[步骤]
                            Step1 -->|条件| Step2[分支]
                            Step2 --> End([结束])
                          ```
                        - 图中体现：主流程、关键判断分支、至少 1 条异常/失败路径；至少 8 个节点
                        - 可用 flowchart 或 sequenceDiagram；禁止只写「流程见上」而无图表源码
                        """);
            }
            chartRules.append("""
                    - Mermaid 语法注意：节点 ID 用英文/数字；标签用 [] / () / {}；换行在 JSON 字符串里写成 \\n
                    - 禁止输出无法解析的伪代码；禁止用 ASCII 艺术代替 Mermaid
                    """);
        }

        return """
                请基于以下原始 PRD 内容，补充指定的章节，以严格的 JSON 格式输出。

                【原始 PRD】
                %s

                %s
                【需要补充的章节】
                %s
                %s
                【输出格式】
                你必须返回一个 JSON 对象（不要用外层 ```json 包裹），结构如下：
                {
                  "sections": [
                    {
                      "type": "structure|flow|data|testcase",
                      "title": "章节标题（如：页面结构图 / 核心业务流程图）",
                      "content": "Markdown 正文；若 type 为 structure 或 flow，必须内嵌 ```mermaid ... ``` 图表"
                    }
                  ]
                }

                要求：
                1. sections 数组与「需要补充的章节」一一对应，type 字段与请求类型一致
                2. 每个 content 不少于 80 字的有效说明（图表节点标签计入）
                3. 只返回 JSON，不要添加解释文字
                """.formatted(
                prdContent != null ? prdContent : "",
                extraInfo.toString(),
                sections.toString(),
                chartRules.toString()
        );
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
            case "structure" -> "必须输出 Mermaid flowchart 页面层级结构图 + 页面清单说明";
            case "flow" -> "必须输出 Mermaid flowchart/sequenceDiagram 核心业务流程（含分支与异常）+ 步骤说明";
            case "data" -> "关键数据字段定义表：字段名、类型、必填、校验规则、示例";
            case "testcase" -> "核心功能测试用例表：场景、前置、步骤、预期、边界";
            default -> "补充内容";
        };
    }
}
