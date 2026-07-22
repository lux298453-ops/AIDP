package com.example.aidocumentplatform.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * XMind 文件解析器。
 *
 * XMind 文件本质是 zip 压缩包：
 *   - 新版 (XMind 2020+): content.json（JSON 格式）
 *   - 旧版 (XMind 8 及以前): content.xml（XML 格式）
 *
 * 输出：Markdown 风格的结构化大纲文本。
 */
@Slf4j
@Component
public class XmindParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 .xmind 文件，返回结构化大纲文本。
     *
     * @param xmindFileBytes .xmind 文件的字节数组
     * @return 结构化大纲（Markdown 列表格式）
     */
    public String parse(byte[] xmindFileBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xmindFileBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                String content = readEntry(zis);

                if (name.equals("content.json")) {
                    log.info("检测到新版 XMind (content.json)");
                    return parseContentJson(content);
                } else if (name.equals("content.xml")) {
                    log.info("检测到旧版 XMind (content.xml)");
                    return parseContentXml(content);
                }
            }
            throw new RuntimeException("XMind 文件中未找到 content.json 或 content.xml");
        } catch (IOException e) {
            log.error("XMind 文件解析失败", e);
            throw new RuntimeException("XMind 文件解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== JSON 解析（新版 XMind） ====================

    private String parseContentJson(String jsonContent) {
        try {
            JsonNode root = objectMapper.readTree(jsonContent);

            // 兼容两种根结构：[{...}] 或 {...}
            if (root.isArray() && !root.isEmpty()) {
                root = root.get(0);
            }

            JsonNode rootTopic = root.path("rootTopic");
            if (rootTopic.isMissingNode()) {
                // 有些版本直接就是 rootTopic
                rootTopic = root;
            }

            StringBuilder sb = new StringBuilder();
            extractTopics(rootTopic, sb, 0);
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("XMind JSON 解析失败", e);
            return jsonContent; // 返回原文作为兜底
        }
    }

    /**
     * 递归提取主题节点，生成缩进大纲文本。
     *
     * XMind JSON 结构:
     * {
     *   "title": "节点名称",
     *   "children": { "attached": [...] }
     *   "notes": { "plain": {} }
     * }
     */
    private void extractTopics(JsonNode topic, StringBuilder sb, int depth) {
        String title = topic.path("title").asText();
        if (!title.isEmpty()) {
            sb.append("  ".repeat(Math.max(0, depth)))
                    .append("- ").append(title).append("\n");
        }

        // 提取备注内容
        JsonNode notes = topic.path("notes");
        if (!notes.isMissingNode()) {
            JsonNode plain = notes.path("plain");
            if (!plain.isMissingNode()) {
                String content = plain.path("content").asText();
                if (!content.isEmpty()) {
                    sb.append("  ".repeat(Math.max(0, depth + 1)))
                            .append("> ").append(content).append("\n");
                }
            }
        }

        // 递归子节点
        JsonNode children = topic.path("children");
        JsonNode attached = children.has("attached")
                ? children.get("attached")
                : children.isArray() ? children : null;

        if (attached != null && attached.isArray()) {
            for (JsonNode child : attached) {
                extractTopics(child, sb, depth + 1);
            }
        }
    }

    // ==================== XML 解析（旧版 XMind） ====================

    private String parseContentXml(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 防止 XXE 攻击
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xmlContent)));

            Element rootSheet = doc.getDocumentElement();
            // 找到第一个 sheet → topic
            NodeList sheets = rootSheet.getElementsByTagName("sheet");
            Element sheet = (Element) (sheets.getLength() > 0 ? sheets.item(0) : rootSheet);

            NodeList topics = sheet.getElementsByTagName("topic");
            if (topics.getLength() == 0) return xmlContent;

            StringBuilder sb = new StringBuilder();
            extractXmlTopics((Element) topics.item(0), sb, 0);
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("XMind XML 解析失败", e);
            return xmlContent;
        }
    }

    private void extractXmlTopics(Element topicEl, StringBuilder sb, int depth) {
        String title = getXmlChildText(topicEl, "title");
        if (!title.isEmpty()) {
            sb.append("  ".repeat(Math.max(0, depth)))
                    .append("- ").append(title).append("\n");
        }

        // 递归 children → topics → topic
        NodeList childrenNodes = topicEl.getElementsByTagName("children");
        if (childrenNodes.getLength() > 0) {
            Element children = (Element) childrenNodes.item(0);
            NodeList childTopics = children.getElementsByTagName("topic");
            for (int i = 0; i < childTopics.getLength(); i++) {
                extractXmlTopics((Element) childTopics.item(i), sb, depth + 1);
            }
        }
    }

    private String getXmlChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return "";
    }

    // ==================== 工具方法 ====================

    private String readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = zis.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
