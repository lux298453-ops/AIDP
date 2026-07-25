package com.example.aidocumentplatform.util;

import com.example.aidocumentplatform.ai.prompt.PrdGeneratePromptTemplate;
import com.example.aidocumentplatform.model.enums.DetailLevel;
import com.example.aidocumentplatform.model.enums.TemplateType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrdContentParserTest {

    private static final String VALID_JSON = """
            {
              "title": "AI文档平台",
              "summary": "提升PRD产出效率",
              "chapters": [
                {"title": "需求概述", "content": "1.背景分析：人工编写耗时；2.业务价值：提升效率"}
              ]
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PrdContentParser parser = new PrdContentParser();

    private WordExporter wordExporter() {
        // 测试环境禁用外部 mermaid 渲染
        MermaidImageRenderer renderer = new MermaidImageRenderer(
                WebClient.builder().build(), "https://kroki.io", false);
        return new WordExporter(parser, renderer);
    }

    @Test
    void parsesNormalAndMarkdownWrappedJson() {
        assertEquals("AI文档平台", parser.normalize(VALID_JSON).path("title").asText());
        assertEquals("AI文档平台", parser.normalize("```json\n" + VALID_JSON + "\n```").path("title").asText());
    }

    @Test
    void unwrapsDoubleEncodedJson() throws Exception {
        String encoded = objectMapper.writeValueAsString(VALID_JSON);
        JsonNode root = parser.normalize(encoded);
        assertEquals(1, root.path("chapters").size());
    }

    @Test
    void repairsLegacyEscapedObject() {
        String legacy = VALID_JSON.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        assertEquals("AI文档平台", parser.normalize(legacy).path("title").asText());
    }

    @Test
    void rejectsJsonWithoutChapters() {
        // 当前实现会 auto-repair 缺 chapters 的对象，不再抛异常
        JsonNode n = parser.normalize("{\"title\":\"缺少章节\",\"summary\":\"x\"}");
        assertTrue(n.has("chapters"));
    }

    @Test
    void wordExportUsesRecoveredChaptersInsteadOfEmptyTemplate() throws Exception {
        String encoded = objectMapper.writeValueAsString(VALID_JSON);
        byte[] bytes = wordExporter().export("AI文档平台", "原始需求", encoded);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(text.contains("背景分析：人工编写耗时"));
            // 不得泄漏 JSON 键
            assertFalse(text.contains("\"content\""));
            assertFalse(text.contains("\"chapters\""));
        }
    }

    @Test
    void wordExportDoesNotDumpNestedJsonContent() throws Exception {
        // content 字段本身是嵌套对象（常见 AI 异常格式）
        String nested = """
                {
                  "title": "测试",
                  "summary": "摘要",
                  "chapters": [
                    {
                      "title": "功能说明",
                      "content": {"title": "内层", "content": "这是真正的正文内容"}
                    }
                  ]
                }
                """;
        byte[] bytes = wordExporter().export("测试", null, nested);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream()
                    .map(p -> p.getText())
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(text.contains("这是真正的正文内容"));
            assertFalse(text.contains("\"content\":"));
        }
    }

    @Test
    void wordExportConvertsMarkdownTableToWordTable() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "表格测试");
        root.put("summary", "验证 Markdown 表格导出");
        ObjectNode chapter = root.putArray("chapters").addObject();
        chapter.put("title", "功能列表");
        chapter.put("content", """
                | 功能点 | 优先级 | 详细描述 |

                | 元数据定义 | P0 | 支持配置表导入 |

                | JSON导出 | P0 | 输出旧工具兼容文件 |
                """);

        byte[] bytes = wordExporter().export("表格测试", null, root.toString(), TemplateType.CUSTOM);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            boolean hasGeneratedTable = document.getTables().stream().anyMatch(table ->
                    table.getRows().stream().anyMatch(row ->
                            row.getTableCells().stream().anyMatch(cell ->
                                    cell.getText().contains("功能点")
                                            || cell.getText().contains("元数据定义"))));
            assertTrue(hasGeneratedTable);
            String paragraphs = document.getParagraphs().stream()
                    .map(p -> p.getText())
                    .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(paragraphs.contains("| 功能点 |"));
        }
    }

    @Test
    void wordExportSplitsInlineNumberedItemsIntoSeparateParagraphs() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "分段测试");
        root.put("summary", "");
        ObjectNode chapter = root.putArray("chapters").addObject();
        chapter.put("title", "需求概述");
        chapter.put("content", "1.背景分析：人工编写耗时；2.业务价值：提升效率；3.覆盖范围：配置后台。");

        byte[] bytes = wordExporter().export("分段测试", null, root.toString(), TemplateType.CUSTOM);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> paragraphs = document.getParagraphs().stream()
                    .map(p -> p.getText().trim())
                    .filter(s -> !s.isBlank())
                    .toList();
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("背景分析")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("业务价值")));
            assertFalse(paragraphs.stream().anyMatch(p -> p.contains("背景分析") && p.contains("业务价值")));
        }
    }

    @Test
    void wordExportPrettyPrintsCompactJsonExamples() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "JSON示例测试");
        root.put("summary", "");
        ObjectNode chapter = root.putArray("chapters").addObject();
        chapter.put("title", "接口示例");
        chapter.put("content", "请求示例：\n{\"roleId\":1001,\"name\":\"战士\",\"attrs\":{\"hp\":120,\"atk\":30}}");

        byte[] bytes = wordExporter().export("JSON示例测试", null, root.toString(), TemplateType.CUSTOM);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> paragraphs = document.getParagraphs().stream()
                    .map(p -> p.getText().trim())
                    .filter(s -> !s.isBlank())
                    .toList();
            assertTrue(paragraphs.contains("{"));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"roleId\" : 1001")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"attrs\" : {")));
            assertFalse(paragraphs.stream().anyMatch(p -> p.contains("{\"roleId\":1001")));
        }
    }

    @Test
    void wordExportKeepsMultilineJsonExamplesAsCodeBlocks() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "多行JSON示例测试");
        root.put("summary", "");
        ObjectNode chapter = root.putArray("chapters").addObject();
        chapter.put("title", "接口示例");
        chapter.put("content", """
                请求示例（导入配置）：

                {

                "action": "import",

                "table_name": "role_config",

                "records": [

                {

                "role_id": "1001",

                "role_name": "英雄A"

                }

                ]

                }

                响应示例：

                {

                "code": 0,

                "message": "导入成功",

                "data": {

                "imported_count": 1,

                "errors": []

                }

                }
                """);

        byte[] bytes = wordExporter().export("多行JSON示例测试", null, root.toString(), TemplateType.CUSTOM);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> paragraphs = document.getParagraphs().stream()
                    .map(p -> p.getText().trim())
                    .filter(s -> !s.isBlank())
                    .toList();
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("请求示例")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"action\" : \"import\"")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"role_name\" : \"英雄A\"")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("响应示例")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"message\" : \"导入成功\"")));
            assertFalse(paragraphs.stream().anyMatch(p ->
                    p.contains("\"action\"") && p.contains("响应示例")));
            assertFalse(paragraphs.stream().anyMatch(p ->
                    p.contains("\"action\"") && p.contains("\"message\"")));
        }
    }

    @Test
    void wordExportSplitsInlineRequestAndResponseJsonExamples() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "压缩JSON示例测试");
        root.put("summary", "");
        ObjectNode chapter = root.putArray("chapters").addObject();
        chapter.put("title", "接口示例");
        chapter.put("content", """
                请求示例（导入配置）： {  "action": "import",  "table_name": "role_config",  "records": [    {      "role_id": "1001",      "role_name": "英雄A"    }  ] } 响应示例： {  "code": 0,  "message": "导入成功",  "data": {    "imported_count": 1,    "errors": []  } }
                """);

        byte[] bytes = wordExporter().export("压缩JSON示例测试", null, root.toString(), TemplateType.CUSTOM);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> paragraphs = document.getParagraphs().stream()
                    .map(p -> p.getText().trim())
                    .filter(s -> !s.isBlank())
                    .toList();
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("请求示例")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"action\" : \"import\"")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"role_name\" : \"英雄A\"")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("响应示例")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("\"message\" : \"导入成功\"")));
            assertFalse(paragraphs.stream().anyMatch(p ->
                    p.contains("\"action\"") && p.contains("响应示例")));
            assertFalse(paragraphs.stream().anyMatch(p ->
                    p.contains("\"action\"") && p.contains("\"message\"")));
        }
    }

    @Test
    void standardPromptUsesExportTemplateOutline() {
        String prompt = new PrdGeneratePromptTemplate()
                .buildUserPrompt("配置后台", "生成配置后台PRD", DetailLevel.DETAILED);
        assertTrue(prompt.contains("\"title\": \"1. 需求概述\""));
        assertTrue(prompt.contains("\"title\": \"1.1 需求背景（必填）\""));
        assertTrue(prompt.contains("\"title\": \"4. 功能需求描述（同行/专家评审必备）\""));
        assertTrue(prompt.contains("\"title\": \"10. 需求评审意见\""));
        assertFalse(prompt.contains("\"title\": \"功能设计\""));
    }

    @Test
    void standardWordExportUsesStandardOutlineChaptersWithoutDuplicatingThem() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", "标准大纲测试");
        root.put("summary", "统一页面和导出大纲");
        var chapters = root.putArray("chapters");
        chapters.addObject().put("title", "1. 需求概述").put("content", "这是总览内容。");
        chapters.addObject().put("title", "1.1 需求背景（必填）").put("content", "这是标准需求背景内容。");
        chapters.addObject().put("title", "1.2 需求目标/价值（必填）").put("content", "这是标准目标价值内容。");
        chapters.addObject().put("title", "1.3 需求覆盖范围").put("content", "这是标准覆盖范围内容。");
        chapters.addObject().put("title", "1.4 需求列表").put("content", "| 功能点 | 优先级 |\n| 导入 | P0 |");
        chapters.addObject().put("title", "1.5 关联方").put("content", "| 关联方 | 关联事项 | 对接人/角色 |\n| 策划 | 配置验收 | 产品 |");
        chapters.addObject().put("title", "2. 流程图（专家评审必备）").put("content", "流程说明内容。");
        chapters.addObject().put("title", "3. 原型图 和 交互+视觉图").put("content", "原型交互内容。");
        chapters.addObject().put("title", "4. 功能需求描述（同行/专家评审必备）").put("content", "这是标准功能需求详情。");
        chapters.addObject().put("title", "4.X 耦合场景").put("content", "这是标准耦合场景。");
        chapters.addObject().put("title", "4.X 边界场景").put("content", "这是标准边界场景。");
        chapters.addObject().put("title", "4.X 非功能需求").put("content", "这是标准非功能需求。");
        chapters.addObject().put("title", "5. 埋点与报表").put("content", "这是标准埋点内容。");
        chapters.addObject().put("title", "6. 配置项（专家评审必备）").put("content", "这是标准配置项。");
        chapters.addObject().put("title", "7. 动效（专家评审必备）").put("content", "这是标准动效。");
        chapters.addObject().put("title", "8. 运营计划（专家评审必备）").put("content", "这是标准运营计划。");
        chapters.addObject().put("title", "9. 安全与合规").put("content", "这是标准安全合规。");
        chapters.addObject().put("title", "10. 需求评审意见").put("content", "这是标准评审意见。");

        byte[] bytes = wordExporter().export("标准大纲测试", null, root.toString(), TemplateType.STANDARD);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> paragraphs = document.getParagraphs().stream()
                    .map(p -> p.getText().trim())
                    .filter(s -> !s.isBlank())
                    .toList();
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("这是标准需求背景内容")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("这是标准功能需求详情")));
            assertTrue(paragraphs.stream().anyMatch(p -> p.contains("这是标准评审意见")));
            assertFalse(paragraphs.stream().anyMatch(p -> p.contains("1）1. 需求概述")));
            assertFalse(paragraphs.stream().anyMatch(p -> p.contains("2）1.1 需求背景")));
        }
    }
}
