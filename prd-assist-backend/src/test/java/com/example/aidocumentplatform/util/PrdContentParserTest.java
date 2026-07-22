package com.example.aidocumentplatform.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertThrows(IllegalArgumentException.class, () -> parser.normalize("{\"title\":\"缺少章节\"}"));
    }

    @Test
    void wordExportUsesRecoveredChaptersInsteadOfEmptyTemplate() throws Exception {
        String encoded = objectMapper.writeValueAsString(VALID_JSON);
        byte[] bytes = new WordExporter().export("AI文档平台", "原始需求", encoded);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(text.contains("背景分析：人工编写耗时"));
        }
    }
}
