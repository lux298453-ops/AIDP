package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

@Component
public class ChartRevisePromptTemplate {

    private static final String SYSTEM = """
            你是信息架构与流程图专家，负责根据用户指令修订图表源码。
            只输出修订后的源码本身，不要解释，不要 markdown 围栏外的任何文字。
            保持与输入图表相同的语言（PlantUML 用 @startuml/@enduml，Mermaid 用 flowchart/sequenceDiagram）。
            """;

    public String getSystemPrompt() {
        return SYSTEM;
    }

    public String buildUserPrompt(String chartType, String chapterTitle,
                                  String currentCode, String instruction) {
        return buildUserPrompt(chartType, chapterTitle, currentCode, instruction, detectLang(currentCode));
    }

    public String buildUserPrompt(String chartType, String chapterTitle,
                                  String currentCode, String instruction, String lang) {
        boolean plantuml = "plantuml".equalsIgnoreCase(lang);
        String langRules = plantuml
                ? """
                  1. 保持合法 PlantUML 语法：以 @startuml 开头、@enduml 结尾
                  2. 节点/活动名用英文或数字标识，标签可用中文；中文用引号包裹避免歧义
                  3. 只返回 @startuml ... @enduml 源码本身，不要 ```plantuml 围栏，不要任何解释
                  """
                : """
                  1. 保持合法 Mermaid 语法（flowchart / sequenceDiagram / graph）
                  2. 节点 ID 用英文或数字；标签可用中文
                  3. 只返回源码本身，不要 ```mermaid 围栏，不要任何解释
                  """;
        return """
                请根据【修改要求】修订【当前图表】，输出完整可渲染的新源码（语言与当前图表一致：%s）。

                【图表类型】%s
                【章节标题】%s

                【当前图表】
                %s

                【修改要求】
                %s

                规则：
                %s
                """.formatted(
                plantuml ? "PlantUML" : "Mermaid",
                chartType != null ? chartType : "chart",
                chapterTitle != null ? chapterTitle : "",
                currentCode != null ? currentCode : "",
                instruction != null ? instruction : "",
                langRules
        );
    }

    private String detectLang(String code) {
        if (code != null && (code.contains("@startuml") || code.contains("@enduml"))) {
            return "plantuml";
        }
        return "mermaid";
    }
}
