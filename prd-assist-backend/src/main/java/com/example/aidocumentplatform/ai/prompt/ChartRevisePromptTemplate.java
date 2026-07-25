package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

@Component
public class ChartRevisePromptTemplate {

    private static final String SYSTEM = """
            你是信息架构与流程图专家，负责根据用户指令修订 Mermaid 图表。
            只输出修订后的 Mermaid 源码，不要解释，不要 markdown 围栏外的文字。
            """;

    public String getSystemPrompt() {
        return SYSTEM;
    }

    public String buildUserPrompt(String chartType, String chapterTitle,
                                  String currentMermaid, String instruction) {
        return """
                请根据【修改要求】修订【当前 Mermaid 图表】，输出完整可渲染的新源码。

                【图表类型】%s
                【章节标题】%s

                【当前 Mermaid】
                %s

                【修改要求】
                %s

                规则：
                1. 保持 Mermaid 合法语法（flowchart / sequenceDiagram / graph）
                2. 节点 ID 用英文或数字；标签可用中文
                3. 只返回源码本身，不要 ```mermaid 围栏，不要任何解释
                """.formatted(
                chartType != null ? chartType : "chart",
                chapterTitle != null ? chapterTitle : "",
                currentMermaid != null ? currentMermaid : "",
                instruction != null ? instruction : ""
        );
    }
}
