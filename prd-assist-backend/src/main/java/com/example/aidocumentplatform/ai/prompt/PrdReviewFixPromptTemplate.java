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

    /**
     * 构建「单条问题局部修复」提示词。
     * 只让模型看到目标章节，避免整篇 PRD 往返，提升速度并降低误改无关章节的概率。
     */
    public String buildSingleChapterPatchPrompt(String prdTitle, String prdSummary, String chapterOutline,
                                                int chapterIndex, String chapterTitle,
                                                String chapterContent, String issueJson) {
        return """
                请根据【待修复问题】只修订【目标章节内容】，不要输出完整 PRD。

                【PRD 标题】
                %s

                【PRD 摘要】
                %s

                【章节大纲】
                %s

                【目标章节】
                chapterIndex=%d
                chapterTitle=%s

                【目标章节当前内容】
                %s

                【待修复问题】
                %s

                局部修订规则：
                1. 只修改该问题对应的目标章节内容，禁止改写其他章节
                2. 围绕 issue 的 location / description / suggestion 补充、改写或明确对应段落
                3. 保持原章节的表达风格、标题层级、表格和列表格式
                4. 不要为了修复单点而重写整章；能补一段就补一段，能改一句就改一句
                5. 禁止写「待补充」「视情况而定」等空泛内容
                6. 表格用 | 分隔，换行用 \\n
                7. 输出格式必须为：
                {
                  "chapterIndex": 0,
                  "title": "原章节标题",
                  "content": "修复后的该章节完整内容",
                  "changeSummary": "一句话说明本次修复"
                }
                8. 只返回 JSON，不要 markdown 代码块，不要解释
                """.formatted(
                blankToDefault(prdTitle, "PRD 文档"),
                blankToDefault(prdSummary, "无"),
                blankToDefault(chapterOutline, "无"),
                chapterIndex,
                blankToDefault(chapterTitle, "未命名章节"),
                blankToDefault(chapterContent, ""),
                issueJson == null ? "{}" : issueJson
        );
    }

    /**
     * 构建「内联精准修复」提示词。
     * AI 只返回被修改的文本片段（oldText → newText），不返回整章内容。
     * 后端用 oldText 在章节中定位并替换，实现原地更新。
     */
    public String buildInlineFixPrompt(String chapterTitle, String chapterContent, String issueJson) {
        return """
                请根据【待修复问题】对【章节内容】进行精准内联修复。

                【章节标题】
                %s

                【章节当前内容】
                %s

                【待修复问题】
                %s

                内联修复规则：
                1. 从章节内容中找到问题对应的原文片段（oldText），给出修复后的版本（newText）
                2. oldText 必须是从章节内容中逐字摘录的原文，不能自己概括；长度 20~500 字
                3. newText 是修复后的文本，替换 oldText 后章节内容应更完善
                4. 如果问题是"缺少某内容"，oldText 填需要插入位置的前一句原文，newText 填"前一句原文 + 新增内容"
                5. 如果问题是"删除某内容"，oldText 填要删除的原文，newText 填空字符串 ""
                6. 如果问题是"改写某内容"，oldText 填原文，newText 填改写后的版本
                7. 禁止写「待补充」「视情况而定」等空泛内容
                8. 输出格式必须为：
                {
                  "oldText": "从章节中摘录的问题原文片段",
                  "newText": "修复后的文本（替换 oldText）",
                  "changeSummary": "一句话说明本次修复了什么"
                }
                9. 只返回 JSON，不要 markdown 代码块，不要解释
                """.formatted(
                blankToDefault(chapterTitle, "未命名章节"),
                blankToDefault(chapterContent, ""),
                issueJson == null ? "{}" : issueJson
        );
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
