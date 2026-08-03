package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.DetailLevel;
import org.springframework.stereotype.Component;

@Component
public class PrdGeneratePromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是资深产品总监，你的唯一任务是：根据用户的简短需求描述，
            独立完成深度分析和拓展，输出一份完整专业的PRD文档。

            工作方法：
            - 先理解需求背后的业务场景和用户痛点
            - 然后推理出完整的功能范围、交互细节、数据模型
            - 最后输出结构完整、可直接交付开发的文档

            铁律（违反任何一条即为失败）：
            1. 禁止把用户原文复制到PRD的任何章节中
            2. 禁止在任何章节中写"待补充"、"根据实际情况"、"视情况而定"
            3. 每个章节必须是你自己分析思考后写出的原创内容
            4. 即使你认为信息不足，也要基于你的专业经验做出合理推断并写出具体内容
            """;

    /**
     * 标准模板 prompt。
     */
    public String buildUserPrompt(String featureName, String description, DetailLevel detailLevel) {
        return buildUserPrompt(featureName, description, detailLevel, null);
    }

    /**
     * 构建用户 prompt。
     *
     * @param customTemplateContent 用户上传的自定义模板纯文本；非空时按该模板的章节结构生成
     */
    public String buildUserPrompt(String featureName, String description,
                                  DetailLevel detailLevel, String customTemplateContent) {
        boolean useCustom = customTemplateContent != null && !customTemplateContent.isBlank();
        if (useCustom) {
            return buildCustomTemplatePrompt(featureName, description, detailLevel, customTemplateContent);
        }
        return buildStandardPrompt(featureName, description);
    }

    private String buildStandardPrompt(String featureName, String description) {
        return """
                用户给了你一个简短的需求，你需要基于它独立完成深度分析和拓展。

                【功能名称】%s
                【用户原始描述】%s

                ※ 以上只是背景信息。下面的PRD文档需要你完全根据自己的分析来写，
                决不允许把上面的原始描述复制到任何章节中。每个字都要是你自己思考的结果。

                ＝＝＝＝ 请输出以下JSON（只返回JSON，不要解释） ＝＝＝＝

                {
                  "title": "功能名称（用你自己的话重写）",
                  "summary": "用你自己的话概述：这个功能的核心价值是什么，解决谁的什么问题",
                  "chapters": [
                    {
                      "title": "1. 需求概述",
                      "content": "概述本需求的业务背景、目标用户、核心目标和交付价值，不要只复述用户原始描述。"
                    },
                    {
                      "title": "1.1 需求背景（必填）",
                      "content": "分析当前业务现状、用户痛点、已有流程的问题，以及为什么现在需要建设该功能。"
                    },
                    {
                      "title": "1.2 需求目标/价值（必填）",
                      "content": "明确业务目标、用户价值、效率或质量提升点，并给出可验证的成功标准。"
                    },
                    {
                      "title": "1.3 需求覆盖范围",
                      "content": "说明本期覆盖的用户角色、业务场景、功能边界、不包含范围和后续可扩展方向。"
                    },
                    {
                      "title": "1.4 需求列表",
                      "content": "用 Markdown 表格列出所有功能点：\\n| 功能点 | 优先级 | 详细描述 | 验收标准 |\\n至少列出5个功能点，P0/P1/P2都要有。"
                    },
                    {
                      "title": "1.5 关联方",
                      "content": "用 Markdown 表格说明涉及的业务方、技术方、运营方、审核方及其职责：\\n| 关联方 | 关联事项 | 对接人/角色 |"
                    },
                    {
                      "title": "2. 流程图（专家评审必备）",
                      "content": "用 PlantUML 绘制流程图（以 @startuml 开头、@enduml 结尾，并包在 ```plantuml 围栏中），描述主流程、分支判断和异常回退路径，并在图后补充关键节点说明。"
                    },
                    {
                      "title": "3. 原型图 和 交互+视觉图",
                      "content": "描述关键页面布局、入口、主操作区、列表/表单/弹窗/状态反馈、空态/错误态和视觉层级。"
                    },
                    {
                      "title": "4. 功能需求描述（同行/专家评审必备）",
                      "content": "按功能模块展开详细需求。必须包含操作流程、权限控制、状态流转、字段校验、接口请求/响应 JSON 示例和验收标准。"
                    },
                    {
                       "title": "4.1 耦合场景",
                       "content": "列出与其他模块、数据表、外部系统、权限、发布流程或历史工具的耦合关系，以及联动规则。"
                     },
                     {
                       "title": "4.2 边界场景",
                       "content": "覆盖空数据、重复提交、导入失败、部分成功、并发编辑、超大数据量、权限不足、网络超时等边界处理。"
                     },
                     {
                       "title": "4.3 非功能需求",
                       "content": "说明性能目标、可用性、兼容性、可维护性、数据一致性和审计追踪要求。"
                     },
                    {
                      "title": "5. 埋点与报表",
                      "content": "列出至少5个核心埋点指标，说明触发时机、统计口径、维度和报表查看方式。"
                    },
                    {
                      "title": "6. 配置项（专家评审必备）",
                      "content": "列出需要后台动态配置的参数、默认值、生效范围、校验规则和发布影响。"
                    },
                    {
                      "title": "7. 动效（专家评审必备）",
                      "content": "说明加载、提交、成功、失败、切换、展开收起等交互动效和持续时间。"
                    },
                    {
                      "title": "8. 运营计划（专家评审必备）",
                      "content": "说明灰度策略、上线节奏、运营配置、用户通知、风险预案和回滚策略。"
                    },
                    {
                      "title": "9. 安全与合规",
                      "content": "说明数据传输、权限隔离、敏感信息保护、操作审计、防误操作、防刷和合规风险。"
                    },
                    {
                      "title": "10. 需求评审意见",
                      "content": "从产品、研发、测试、运营角度列出评审关注点、待确认事项和建议验收检查清单。"
                    }
                  ]
                }

                要求：
                1. chapters 数量、顺序、title 必须严格等于上方标准模板大纲，不得合并、删减或重命名
                2. 每个 content 不少于 120 字，用 \\"\\n\\" 换行，用 \\"|\\" 画表格
                3. JSON 示例必须使用多行格式，禁止压缩成一行
                4. 图表一律使用 PlantUML，禁止使用 Mermaid。格式示例：
                   ```plantuml
                   @startuml
                   start
                   :用户发起需求;
                   if (信息完整?) then (是)
                     :进入流程;
                   else (否)
                     :提示补全;
                   endif
                   stop
                   @enduml
                   ```
                   也可以使用 activity / sequence / component / state 等图型；节点标签用中文。
                 5. PlantUML 语法硬性要求：每条语句占一行；skinparam 一行只写一个参数，
                   参数名与值之间必须有空格（写 RoundCorner 12，禁止 RoundCorner12、BackgroundColor#F8FBFF 这类粘连写法）；
                   skinparam ... { } 块的 { 与 } 必须各自独占一行，块内每个参数单独一行。
                   允许 left to right direction 与 skinparam packageStyle rectangle 同时使用，无冲突。
                你的输出质量决定了开发团队能否直接开始编码，请认真对待。
                只返回JSON。
                """.formatted(featureName, description);
    }

    /**
     * 自定义模板模式：严格按用户模板的章节标题与顺序组织 PRD。
     */
    private String buildCustomTemplatePrompt(String featureName, String description,
                                             DetailLevel detailLevel, String customTemplateContent) {
        // 防止超长模板撑爆上下文：截断到约 12000 字
        String templateBody = customTemplateContent.length() > 12000
                ? customTemplateContent.substring(0, 12000) + "\n…(模板后续内容已截断)"
                : customTemplateContent;
        String detailHint = detailLevel != null && "CONCISE".equals(detailLevel.name())
                ? "每个一级章节 content 约 100-150 字，精炼概括即可；二级小节可略短。"
                : "每个一级章节 content 不少于 150 字，写得充分、可交付开发；二级小节也要有实质内容。";

        return """
                用户给了你一个简短的需求，以及一份【自定义 PRD 模板】。
                你的任务是：先从模板中识别完整的章节目录（含编号与层级），
                再按该目录逐章填充内容。

                ⚠ 严禁事项：
                - 禁止使用系统内置的「需求概述/功能设计/交互流程/UI设计/数据设计/异常处理/安全与性能」7 章结构
                - 禁止擅自合并、删减、重命名模板中的章节
                - 禁止把用户原始描述原样复制进任何章节

                【功能名称】%s
                【用户原始描述】%s

                【自定义模板原文】（含 # 标记的行是标题层级；| 分隔的是表格骨架）
                %s

                工作步骤：
                1. 从模板中提取所有章节标题（含 1. / 1.1 / 1.1.1 或 # / ## 标记的行），按出现顺序排列
                2. 每个标题对应 chapters 中的一项，title 字段写模板中的原标题（保留编号）
                3. content 字段写该章节在本次需求下的完整正文；模板中的示例/说明只作写作指引，不要照抄
                4. 若模板含「修订记录 / 关联方」等表格，在 content 中用 Markdown 表格填写合理内容

                ＝＝＝＝ 请输出以下 JSON（只返回 JSON，不要解释） ＝＝＝＝

                {
                  "title": "功能名称（用你自己的话重写）",
                  "summary": "用一句话概述本功能的核心价值（对应模板中的「一句话需求」如有）",
                  "chapters": [
                    {
                      "title": "模板中第 1 个章节的完整标题（含编号）",
                      "content": "该章节正文，可用 \\"\\n\\" 换行，可用 \\"|\\" 画表格"
                    },
                    {
                      "title": "模板中第 2 个章节的完整标题（含编号）",
                      "content": "……"
                    }
                  ]
                }

                要求：
                1. chapters 数量、顺序、标题必须覆盖模板中全部主要章节（一级 + 重要二级），不得只写前几章就结束
                2. %s
                3. 禁止写「待补充」「根据实际情况」「视情况而定」「（没有则填无）」作为唯一内容——至少写一句合理推断
                4. 只返回 JSON
                """.formatted(featureName, description, templateBody, detailHint);
    }

    public String getSystemPrompt() { return SYSTEM_PROMPT; }
}
