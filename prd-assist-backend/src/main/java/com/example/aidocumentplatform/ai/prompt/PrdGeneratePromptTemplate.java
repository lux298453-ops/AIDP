package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.DetailLevel;
import org.springframework.stereotype.Component;

/**
 * PRD 生成的 Prompt 模板。
 *
 * 要求 AI 输出结构化 JSON，包含：
 *   需求分析、功能设计、交互流程、UI/UX说明、技术需求 五个章节。
 */
@Component
public class PrdGeneratePromptTemplate {

    /** 系统角色设定 */
    private static final String SYSTEM_PROMPT = """
            你是一位资深产品策划专家，擅长撰写专业的产品需求文档（PRD）。
            你的输出必须严格遵循指定格式，确保内容清晰、可执行、可直接交付给开发团队。
            请始终以中文输出，专业术语可保留英文。
            """;

    /**
     * 构建 user prompt —— 根据用户输入和配置生成指令。
     */
    public String buildUserPrompt(String featureName, String description, DetailLevel detailLevel) {
        String detailInstruction = (detailLevel == DetailLevel.CONCISE)
                ? "每个章节只需列出核心要点，语言精炼。"
                : "每个章节需要详细展开，包含具体描述、边界条件、验收标准。";

        return """
                请根据以下信息，生成一份结构化的 PRD 文档，以严格的 JSON 格式输出。

                【功能名称】%s

                【需求描述】%s

                【输出格式】
                你必须返回一个 JSON 对象（不要包含 markdown 代码块标记），结构如下：
                {
                  "title": "功能名称",
                  "summary": "功能概述（一句话）",
                  "chapters": [
                    {
                      "title": "需求分析",
                      "content": "详细说明用户需求、业务背景、目标用户"
                    },
                    {
                      "title": "功能设计",
                      "content": "核心功能点、功能边界、操作流程"
                    },
                    {
                      "title": "交互流程",
                      "content": "用户操作步骤、页面跳转关系、状态变化"
                    },
                    {
                      "title": "UI/UX说明",
                      "content": "页面布局要点、交互元素、视觉规范"
                    },
                    {
                      "title": "技术需求",
                      "content": "接口需求、数据字段、性能指标、兼容性要求"
                    }
                  ]
                }

                【要求】
                %s
                请严格按 JSON 格式返回，不要添加任何解释文字。
                """.formatted(featureName, description, detailInstruction);
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }
}
