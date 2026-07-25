package com.example.aidocumentplatform.ai.prompt;

import org.springframework.stereotype.Component;

/**
 * 根据审查问题列表修订 PRD 的 Prompt。
 */
@Component
public class PrdReviewFixPromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是资深产品经理，负责根据评审意见修订 PRD 文档。
            你必须在保留原有章节结构与标题的前提下，只修改与问题相关的内容。
            禁止删减无关章节，禁止写「待补充」「视情况而定」。
            请始终以中文输出，且只返回 JSON。
            """;

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * @param prdJson      当前 PRD 的 JSON 字符串（title/summary/chapters）
     * @param issuesJson   待修复问题列表 JSON
     */
    public String buildUserPrompt(String prdJson, String issuesJson) {
        return """
                请根据【待修复问题】修订【当前 PRD】，输出修订后的完整 PRD JSON。

                【当前 PRD】
                %s

                【待修复问题】
                %s

                修订规则：
                1. 尽量保持 chapters 的数量、顺序与 title 不变；仅在问题明确要求新增章节时才新增
                2. 针对每条问题的 location / description / suggestion，在对应章节 content 中落实修改
                3. 不要删除其他无关章节内容；可润色，但不要无故缩短
                4. 表格用 | 分隔，换行用 \\n
                5. 输出格式必须为：
                {
                  "title": "...",
                  "summary": "...",
                  "chapters": [
                    { "title": "...", "content": "..." }
                  ]
                }
                6. 只返回 JSON，不要 markdown 代码块，不要解释
                """.formatted(
                prdJson == null ? "{}" : prdJson,
                issuesJson == null ? "[]" : issuesJson
        );
    }
}
