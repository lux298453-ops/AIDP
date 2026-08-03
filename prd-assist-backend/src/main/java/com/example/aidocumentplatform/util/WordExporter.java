package com.example.aidocumentplatform.util;

import com.example.aidocumentplatform.model.enums.TemplateType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PRD → Word 导出。
 *
 * <ul>
 *   <li>严格解析 content JSON，禁止把原始 JSON 写进文档</li>
 *   <li>章节 title → 标题；content → Markdown 格式化正文</li>
 *   <li>Mermaid / 图片 URL 渲染为高清 PNG（≥1200px）后插入</li>
 *   <li>STANDARD / CUSTOM 两种模板</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordExporter {

    private static final Pattern MERMAID_FENCE = Pattern.compile(
            "```mermaid[ \\t]*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLANTUML_FENCE = Pattern.compile(
            "```plantuml[ \\t]*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_PLANTUML = Pattern.compile(
            "(?s)@startuml[\\s\\S]*?@enduml");
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```([a-zA-Z0-9_+-]*)[ \\t]*\\n([\\s\\S]*?)```");
    private static final Pattern MD_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern MD_ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|_(.+?)_");
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST_LINE = Pattern.compile("^(\\s*)([-*+]|\\d+[\\.、)])\\s*(.+)$");
    private static final Pattern JSON_LEAK = Pattern.compile(
            "(?s)^\\s*[{\\[]\\s*\"(title|content|chapters|summary|type)\".*");

    /** Word 页面可显示最大宽度（英寸）≈ A4 可用宽 */
    private static final double MAX_DISPLAY_INCHES = 6.0;
    private static final double MAX_DISPLAY_HEIGHT_INCHES = 8.5;

    private final PrdContentParser prdContentParser;
    private final MermaidImageRenderer mermaidImageRenderer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] export(String title, String description, String contentJson) {
        return export(title, description, contentJson, TemplateType.STANDARD, Collections.emptyMap());
    }

    public byte[] export(String title, String description, String contentJson, TemplateType templateType) {
        return export(title, description, contentJson, templateType, Collections.emptyMap());
    }

    public byte[] export(String title, String description, String contentJson,
                         TemplateType templateType, Map<String, byte[]> chartImages) {
        Map<String, byte[]> images = chartImages != null
                ? new ConcurrentHashMap<>(chartImages)
                : new ConcurrentHashMap<>();

        // 丢弃低清前端图（宽度 < 1000），强制后端高清重渲
        images.entrySet().removeIf(e -> {
            int w = MermaidImageRenderer.imageWidth(e.getValue());
            if (w > 0 && w < 1000) {
                log.info("丢弃低清前端图 key={}, width={}", e.getKey(), w);
                return true;
            }
            return e.getValue() == null || e.getValue().length == 0;
        });

        JsonNode root = safeNormalize(contentJson);
        fillMissingCharts(root, images);

        if (templateType == TemplateType.CUSTOM) {
            return exportByChapters(title, description, root, images);
        }
        return exportStandard(title, description, root, images);
    }

    // ==================== 解析 / 清洗 ====================

    private JsonNode safeNormalize(String contentJson) {
        try {
            return prdContentParser.normalize(contentJson);
        } catch (Exception e) {
            log.warn("content 解析失败，尝试兜底: {}", e.getMessage());
            try {
                JsonNode n = objectMapper.readTree(contentJson);
                if (n != null && n.isObject()) return n;
            } catch (Exception ignored) { /* fallthrough */ }
            // 最终兜底：不把 raw JSON 当正文
            try {
                return objectMapper.readTree(
                        "{\"title\":\"PRD文档\",\"summary\":\"\",\"chapters\":[]}");
            } catch (Exception ex) {
                throw new RuntimeException("无法解析 PRD content", ex);
            }
        }
    }

    /**
     * 安全读取章节/字段文本：
     * - 文本节点直接 asText
     * - 对象/数组节点：若是 {title,content} 则取 content；否则绝不 toString 整段 JSON
     * - 清理字面量 \\n 与 JSON 泄漏
     */
    private String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) {
            return cleanText(node.asText());
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isObject()) {
            // 常见嵌套：{"title":"...","content":"..."}
            if (node.has("content")) {
                return textOf(node.get("content"));
            }
            if (node.has("text")) {
                return textOf(node.get("text"));
            }
            if (node.has("markdown")) {
                return textOf(node.get("markdown"));
            }
            // 把对象的各字段拼成可读段落，而不是 dump JSON
            StringBuilder sb = new StringBuilder();
            node.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if ("type".equals(k) || "id".equals(k)) return;
                String v = textOf(e.getValue());
                if (v.isBlank()) return;
                if (sb.length() > 0) sb.append('\n');
                if (!"content".equals(k) && !"text".equals(k)) {
                    sb.append(k).append("：");
                }
                sb.append(v);
            });
            return sb.toString().trim();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String t = textOf(item);
                if (t.isBlank()) continue;
                if (sb.length() > 0) sb.append('\n');
                // 列表项
                if (!t.startsWith("-") && !t.startsWith("•")) {
                    sb.append("• ").append(t);
                } else {
                    sb.append(t);
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private String cleanText(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\r\n", "\n").replace("\r", "\n");
        // 字面量转义
        if (s.contains("\\n") || s.contains("\\\"")) {
            s = s.replace("\\n", "\n")
                    .replace("\\r", "")
                    .replace("\\t", "  ")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        s = s.trim();
        // 若整段看起来像 JSON 泄漏，尝试再解析一次
        if (looksLikeJson(s)) {
            try {
                JsonNode n = objectMapper.readTree(s);
                String recovered = textOf(n);
                if (!recovered.isBlank() && !looksLikeJson(recovered)) {
                    return recovered;
                }
                // 解析成功但是对象：仍不 dump
                if (n.isObject() || n.isArray()) {
                    return textOf(n);
                }
            } catch (Exception ignored) { /* keep cleaned text */ }
            // 无法解析的 JSON 残骸：去掉明显 key 前缀
            s = s.replaceAll("(?m)^\\s*\"(title|content|type|summary|chapters)\"\\s*:\\s*\"?", "")
                    .replaceAll("\"\\s*,?\\s*$", "")
                    .replaceAll("^[{\\[\\s,]+|[}\\]\\s,]+$", "")
                    .trim();
        }
        return s;
    }

    private boolean looksLikeJson(String s) {
        if (s == null || s.length() < 8) return false;
        String t = s.trim();
        if ((t.startsWith("{") && t.contains("\"content\""))
                || (t.startsWith("[") && t.contains("\"title\""))
                || JSON_LEAK.matcher(t).find()) {
            return true;
        }
        return t.startsWith("{") && t.endsWith("}") && t.contains("\":");
    }

    // ==================== 图表填充 ====================

    private void fillMissingCharts(JsonNode root, Map<String, byte[]> images) {
        if (!mermaidImageRenderer.isEnabled()) return;
        try {
            JsonNode chapters = root.path("chapters");

            // 1) 收集缺失图（summary + 各章节），并行渲染。
            //    PlantUML/Mermaid 渲染器线程安全、CPU 密集，并行可把 N 张串行降到 ~1 张时长。
            List<Object[]> jobs = new ArrayList<>();
            String summary = textOf(root.path("summary"));
            if (!summary.isBlank() && !images.containsKey("summary")) {
                jobs.add(new Object[]{"summary", summary});
            }
            if (chapters.isArray()) {
                for (int i = 0; i < chapters.size(); i++) {
                    String idxKey = String.valueOf(i);
                    if (images.containsKey(idxKey)) continue;
                    String content = textOf(chapters.get(i).path("content"));
                    if (!content.isBlank()) jobs.add(new Object[]{idxKey, content});
                }
            }
            if (!jobs.isEmpty()) {
                int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, jobs.size()));
                List<Future<?>> futures = new ArrayList<>();
                for (Object[] job : jobs) {
                    String key = (String) job[0];
                    String content = (String) job[1];
                    futures.add(pool.submit(() -> {
                        byte[] png = mermaidImageRenderer.resolveChartPng(content);
                        if (png != null) images.put(key, png);
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ignored) { /* 单张失败不影响导出 */ }
                }
                pool.shutdown();
            }

            // 2) 语义 key（flow/structure）：STANDARD 骨架第 2/3 章使用
            if (chapters.isArray()) {
                for (int i = 0; i < chapters.size(); i++) {
                    JsonNode ch = chapters.get(i);
                    String type = ch.path("type").asText("").toLowerCase();
                    String title = textOf(ch.path("title"));
                    byte[] png = images.get(String.valueOf(i));
                    if (png == null) continue;
                    if (("flow".equals(type) || title.contains("流程")) && !images.containsKey("flow")) {
                        images.put("flow", png);
                    }
                    if (("structure".equals(type) || title.contains("结构") || title.contains("页面结构"))
                            && !images.containsKey("structure")) {
                        images.put("structure", png);
                    }
                }
            }
            log.info("Word 导出图表就绪: keys={}, sizes={}",
                    images.keySet(),
                    images.entrySet().stream()
                            .map(e -> e.getKey() + "=" + MermaidImageRenderer.imageWidth(e.getValue()) + "px")
                            .toList());
        } catch (Exception e) {
            log.warn("自动填充图表失败: {}", e.getMessage());
        }
    }

    // ==================== CUSTOM 导出 ====================

    private byte[] exportByChapters(String title, String description, JsonNode root,
                                    Map<String, byte[]> chartImages) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            JsonNode chapters = root.path("chapters");
            String summary = textOf(root.path("summary"));
            String docTitle = title != null && !title.isBlank()
                    ? title : textOf(root.path("title"));
            if (docTitle.isBlank()) docTitle = "PRD文档";

            addHeading(doc, docTitle, 0);
            addPlainParagraph(doc, "生成日期: " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            addPlainParagraph(doc, "模板类型: 自定义模板");
            addBlank(doc);

            if (!summary.isBlank()) {
                addHeading(doc, "一句话需求", 1);
                writeBodyWithCharts(doc, summary, resolveImage(chartImages, "summary"));
                addBlank(doc);
            }

            if (chapters.isArray() && !chapters.isEmpty()) {
                for (int i = 0; i < chapters.size(); i++) {
                    JsonNode ch = chapters.get(i);
                    String chTitle = textOf(ch.path("title"));
                    String chContent = textOf(ch.path("content"));
                    String chType = ch.path("type").asText("").toLowerCase();
                    byte[] png = resolveImage(chartImages, String.valueOf(i), typeKey(chType));
                    if (chTitle.isBlank() && chContent.isBlank() && png == null) continue;

                    int level = headingLevelFromTitle(chTitle);
                    addHeading(doc, chTitle.isBlank() ? "未命名章节" : chTitle, level);
                    writeBodyWithCharts(doc, chContent, png);
                    addBlank(doc);
                }
            } else {
                addPlainParagraph(doc, "（文档暂无章节内容）");
            }

            if (description != null && !description.isBlank() && !looksLikeJson(description)) {
                addHeading(doc, "附录：用户原始需求输入", 2);
                writeMarkdown(doc, cleanText(description));
            }

            doc.write(bos);
            log.info("Word 自定义导出成功: title={}, chapters={}, images={}, size={}bytes",
                    docTitle, chapters.isArray() ? chapters.size() : 0, chartImages.size(), bos.size());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Word导出失败: " + e.getMessage(), e);
        }
    }

    // ==================== STANDARD 导出 ====================

    private byte[] exportStandard(String title, String description, JsonNode root,
                                  Map<String, byte[]> chartImages) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            JsonNode chapters = root.path("chapters");
            String summary = textOf(root.path("summary"));
            Set<Integer> consumed = new HashSet<>();
            consumeStandardChapters(chapters, consumed);
            String docTitle = title != null && !title.isBlank()
                    ? title : textOf(root.path("title"));
            if (docTitle.isBlank()) docTitle = "PRD文档";

            addHeading(doc, docTitle, 0);
            addPlainParagraph(doc, "生成日期: " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            addBlank(doc);
            addPlainParagraph(doc, "TAPD链接地址：");
            addPlainParagraph(doc, "交互文档地址：");
            addPlainParagraph(doc, "视觉文档地址：");
            addPlainParagraph(doc, "资源切图路径：");
            addBlank(doc);

            addHeading(doc, "修订记录", 2);
            addTable(doc, new String[]{"版本号", "修订人", "修订日期", "修订描述"},
                    new String[][]{{"V1.0", "AI", LocalDate.now().toString(), "初始版本"}});
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "1. 需求概述", null), "1. 需求概述"), 1);
            if (summary.length() > 5) {
                writeMarkdown(doc, "一句话需求：" + summary);
            }
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "1.1 需求背景", null), "1.1 需求背景（必填）"), 2);
            writeMarkdown(doc, standardOrFallback(chapters, "1.1 需求背景", consumed, "需求概述", "需求背景"));
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "1.2 需求目标", null), "1.2 需求目标/价值（必填）"), 2);
            writeMarkdown(doc, standardOrFallback(chapters, "1.2 需求目标", consumed, "需求概述", "目标", "价值", "业务价值"));
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "1.3 需求覆盖范围", null), "1.3 需求覆盖范围"), 2);
            writeMarkdown(doc, standardOrFallback(chapters, "1.3 需求覆盖范围", consumed, "需求概述", "覆盖", "范围", "场景"));
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "1.4 需求列表", null), "1.4 需求列表"), 2);
            writeMarkdown(doc, standardOrFallback(chapters, "1.4 需求列表", consumed, "功能设计", "功能点", "功能列表"));
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "1.5 关联方", null), "1.5 关联方"), 2);
            String stakeholderContent = chapterContentByTitle(chapters, "1.5 关联方", consumed);
            if (!stakeholderContent.isBlank()) {
                writeMarkdown(doc, stakeholderContent);
            } else {
                addTable(doc, new String[]{"关联方", "关联事项", "对接人"},
                        new String[][]{{"无", "无", "无"}});
            }
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "2. 流程图", null), "2. 流程图（专家评审必备）"), 1);
            insertSemanticChart(doc, chapters, chartImages, consumed, "flow",
                    new String[]{"交互流程", "流程", "流程图"}, "flow");
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "3. 原型图", null), "3. 原型图 和 交互+视觉图"), 1);
            insertSemanticChart(doc, chapters, chartImages, consumed, "structure",
                    new String[]{"UI设计", "原型", "布局", "视觉", "UI", "页面结构", "结构"}, "structure");
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "4. 功能需求描述", null), "4. 功能需求描述（同行/专家评审必备）"), 1);
            addHeading(doc, "名词解释", 2);
            addPlainParagraph(doc, "（没有则填无）");
            addBlank(doc);

            String functionContent = chapterContentByTitle(chapters, "4. 功能需求描述", consumed);
            if (!functionContent.isBlank()) {
                writeMarkdown(doc, functionContent);
                addBlank(doc);
            }

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "4.1 耦合场景", null), "4.1 耦合场景"), 3);
            writeMarkdown(doc, standardOrFallback(chapters, "4.1 耦合场景", consumed, "异常处理", "耦合", "异常"));
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "4.2 边界场景", null), "4.2 边界场景"), 3);
            writeMarkdown(doc, standardOrFallback(chapters, "4.2 边界场景", consumed, "异常处理", "边界"));
            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "4.3 非功能需求", null), "4.3 非功能需求"), 3);
            writeMarkdown(doc, standardOrFallback(chapters, "4.3 非功能需求", consumed, "安全与性能", "非功能", "性能"));
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "5. 埋点与报表", null), "5. 埋点与报表"), 1);
            addPlainParagraph(doc, "核心关注数据：");
            writeMarkdown(doc, standardOrFallback(chapters, "5. 埋点与报表", consumed, "安全与性能", "埋点", "指标", "数据统计"));
            addPlainParagraph(doc, "具体埋点文档：需与数分同学对齐并录入obus");
            addPlainParagraph(doc, "报表：（没有则填无）");
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "6. 配置项", null), "6. 配置项（专家评审必备）"), 1);
            writeMarkdown(doc, standardOrFallback(chapters, "6. 配置项", consumed, "安全与性能", "配置"));
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "7. 动效", null), "7. 动效（专家评审必备）"), 1);
            writeMarkdown(doc, standardOrDefault(chapters, "7. 动效", consumed, "（没有则填无）"));
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "8. 运营计划", null), "8. 运营计划（专家评审必备）"), 1);
            writeMarkdown(doc, standardOrDefault(chapters, "8. 运营计划", consumed, "（没有则填无）"));
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "9. 安全与合规", null), "9. 安全与合规"), 1);
            writeMarkdown(doc, standardOrFallback(chapters, "9. 安全与合规", consumed, "安全与性能", "安全", "加密", "合规"));
            addBlank(doc);

            addHeading(doc, headingOr(chapterTitleByTitle(chapters, "10. 需求评审意见", null), "10. 需求评审意见"), 1);
            writeMarkdown(doc, standardOrDefault(chapters, "10. 需求评审意见", consumed, "（必填，没有则填无）"));
            addBlank(doc);

            // 补充章节：增强新增的 数据字段/测试用例/页面结构图 等，以及标准骨架未覆盖的用户自定义章节
            if (chapters.isArray() && !chapters.isEmpty()) {
                for (int i = 0; i < chapters.size(); i++) {
                    if (consumed.contains(i)) continue;
                    JsonNode ch = chapters.get(i);
                    String chTitle = textOf(ch.path("title"));
                    String chContent = textOf(ch.path("content"));
                    String chType = ch.path("type").asText("").toLowerCase();
                    byte[] png = resolveImage(chartImages, String.valueOf(i), typeKey(chType));
                    if (chTitle.isBlank() && chContent.isBlank() && png == null) continue;
                    addHeading(doc, chTitle.isBlank() ? "补充章节" : chTitle, headingLevelFromTitle(chTitle));
                    writeBodyWithCharts(doc, chContent, png);
                    addBlank(doc);
                }
            }

            if (description != null && !description.isBlank() && !looksLikeJson(description)) {
                addHeading(doc, "附录：用户原始需求输入", 2);
                writeMarkdown(doc, cleanText(description));
            }

            doc.write(bos);
            log.info("Word标准导出成功: title={}, images={}, size={}bytes",
                    docTitle, chartImages.size(), bos.size());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Word导出失败: " + e.getMessage(), e);
        }
    }

    private void insertSemanticChart(XWPFDocument doc, JsonNode chapters,
                                     Map<String, byte[]> chartImages,
                                     Set<Integer> consumed,
                                     String semanticKey,
                                     String[] titleKeywords,
                                     String typeHint) {
        byte[] png = chartImages.get(semanticKey);
        String content = null;
        if (chapters != null && chapters.isArray()) {
            for (int i = 0; i < chapters.size(); i++) {
                JsonNode ch = chapters.get(i);
                String title = textOf(ch.path("title"));
                String type = ch.path("type").asText("").toLowerCase();
                boolean match = typeHint.equals(type);
                if (!match) {
                    for (String kw : titleKeywords) {
                        if (title.contains(kw)) { match = true; break; }
                    }
                }
                if (match) {
                    if (consumed != null) consumed.add(i);
                    if (png == null) {
                        png = resolveImage(chartImages, String.valueOf(i), typeKey(type));
                    }
                    if (content == null) content = textOf(ch.path("content"));
                    break;
                }
            }
        }
        if (content == null) {
            content = firstChapterContent(chapters, titleKeywords[0],
                    titleKeywords.length > 1
                            ? java.util.Arrays.copyOfRange(titleKeywords, 1, titleKeywords.length)
                            : new String[0]);
        }
        writeBodyWithCharts(doc, content, png);
    }

    // ==================== 正文写入（图 + Markdown） ====================

    /**
     * 写入章节正文：
     * 1) 若有预渲染 PNG → 插入高清图
     * 2) 否则尝试从正文再渲 mermaid / 下图
     * 3) 去掉图表源码后，以 Markdown 写正文
     */
    private void writeBodyWithCharts(XWPFDocument doc, String content, byte[] pngBytes) {
        String text = content == null ? "" : content;
        byte[] png = pngBytes;

        if ((png == null || png.length == 0) && mermaidImageRenderer.isEnabled()) {
            png = mermaidImageRenderer.resolveChartPng(text);
        }

        if (png != null && png.length > 0) {
            addImage(doc, png);
            text = stripChartSource(text);
        } else if (mermaidImageRenderer.hasRenderableChart(text)) {
            addPlainParagraph(doc, "（图表渲染失败，请检查源码或重新导出）");
            text = stripChartSource(text);
        }

        if (text != null && !text.isBlank()) {
            writeMarkdown(doc, text);
        }
    }

    /** 去掉图表源码（plantuml/mermaid 围栏、裸 flowchart、裸 @startuml 块）、Markdown 图片与 diagram URL */
    private String stripChartSource(String content) {
        if (content == null || content.isBlank()) return "";
        String text = content.replace("\\n", "\n");
        String rest = MERMAID_FENCE.matcher(text).replaceAll("");
        rest = PLANTUML_FENCE.matcher(rest).replaceAll("");
        rest = RAW_PLANTUML.matcher(rest).replaceAll("");
        rest = MD_IMAGE.matcher(rest).replaceAll("");
        rest = rest.replaceAll("(?im)^\\s*(structureDiagramUrl|flowDiagramUrl|diagramUrl|imageUrl)\\s*[:=].*$", "").trim();

        if (rest.matches("(?is)(?s).*^\\s*(flowchart|graph|sequenceDiagram)\\b.*")) {
            StringBuilder sb = new StringBuilder();
            boolean skip = false;
            for (String line : rest.split("\n", -1)) {
                String t = line.trim();
                if (!skip && t.matches("(?i)^(flowchart|graph|sequenceDiagram)\\b.*")) {
                    skip = true;
                    continue;
                }
                if (skip) {
                    if (t.isEmpty()) { skip = false; continue; }
                    if (t.matches(".*(--|==>|-->|\\[|\\]|subgraph|participant).*")
                            || t.matches("^[A-Za-z][\\w]*([\\[{(].*)?$")
                            || t.matches("(?i)^(end|style |classDef|Note)\\b.*")) {
                        continue;
                    }
                    skip = false;
                }
                if (!skip) sb.append(line).append('\n');
            }
            rest = sb.toString().trim();
        }
        return cleanText(rest);
    }

    /**
     * 轻量 Markdown → Word：
     * - 标题 # ##
     * - 列表 - * 1.
     * - Markdown 表格 | A | B |
     * - 代码块（非 mermaid 以等宽字体输出）
     * - 行内 **加粗** `code` [链接](url)
     * - 普通段落
     */
    private void writeMarkdown(XWPFDocument doc, String markdown) {
        if (markdown == null || markdown.isBlank()) return;
        String src = cleanText(markdown);
        if (src.isBlank() || looksLikeJson(src)) {
            // 二次防御：绝不把 JSON 写进文档
            if (looksLikeJson(src)) {
                String prettyJson = prettyPrintJsonExample(src);
                if (!prettyJson.isBlank()) {
                    addCodeBlock(doc, prettyJson);
                } else {
                    log.warn("跳过疑似 JSON 泄漏的正文片段: {}...", src.substring(0, Math.min(80, src.length())));
                }
                return;
            }
            return;
        }

        // 先切代码块
        List<Block> blocks = splitBlocks(src);
        for (Block block : blocks) {
            if (block.code) {
                String lang = block.lang == null ? "" : block.lang.trim().toLowerCase();
                if ("mermaid".equals(lang) || "plantuml".equals(lang)) {
                    // 兜底：若 strip 漏掉，这里再渲一次
                    byte[] png = mermaidImageRenderer.renderCodeFence(lang, block.text);
                    if (png != null) addImage(doc, png);
                    continue;
                }
                addCodeBlock(doc, block.text);
                continue;
            }
            writeTextBlock(doc, block.text);
        }
    }

    private void writeTextBlock(XWPFDocument doc, String text) {
        String[] lines = expandInlineNumberedItems(text).split("\n", -1);
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String line = rawLine;
            JsonBlock jsonBlock = collectJsonBlock(lines, i);
            if (jsonBlock != null) {
                flushParagraph(doc, para);
                addCodeBlock(doc, jsonBlock.prettyJson());
                i = jsonBlock.endIndex();
                continue;
            }
            JsonSegment jsonSegment = findInlineJsonSegment(line);
            if (jsonSegment != null) {
                flushParagraph(doc, para);
                if (!jsonSegment.before().isBlank()) {
                    addInlineMarkdownParagraph(doc, jsonSegment.before().trim());
                }
                addCodeBlock(doc, jsonSegment.prettyJson());
                if (!jsonSegment.after().isBlank()) {
                    writeTextBlock(doc, jsonSegment.after().trim());
                }
                continue;
            }
            String prettyJson = prettyPrintJsonExample(line);
            if (!prettyJson.isBlank()) {
                flushParagraph(doc, para);
                addCodeBlock(doc, prettyJson);
                continue;
            }
            if (isMarkdownTableLine(line)) {
                flushParagraph(doc, para);
                List<String> tableLines = new ArrayList<>();
                while (i < lines.length) {
                    if (isMarkdownTableLine(lines[i])) {
                        tableLines.add(lines[i]);
                        i++;
                        continue;
                    }
                    if (lines[i].trim().isEmpty()
                            && i + 1 < lines.length
                            && isMarkdownTableLine(lines[i + 1])) {
                        i++;
                        continue;
                    }
                    break;
                }
                i--;
                addMarkdownTable(doc, tableLines);
                continue;
            }
            // 空行 → 刷段落
            if (line.trim().isEmpty()) {
                flushParagraph(doc, para);
                continue;
            }
            Matcher hm = HEADING_LINE.matcher(line.trim());
            if (hm.matches()) {
                flushParagraph(doc, para);
                int level = Math.min(hm.group(1).length(), 3);
                addHeading(doc, hm.group(2).trim(), level);
                continue;
            }
            Matcher lm = LIST_LINE.matcher(line);
            if (lm.matches()) {
                flushParagraph(doc, para);
                String bullet = lm.group(2);
                String body = lm.group(3);
                boolean ordered = bullet.matches("\\d+[\\.、)]");
                addListItem(doc, ordered ? bullet + " " + body : body, ordered);
                continue;
            }
            // 引用 >
            if (line.trim().startsWith(">")) {
                flushParagraph(doc, para);
                addQuote(doc, line.trim().replaceFirst("^>+\\s*", ""));
                continue;
            }
            // 水平线
            if (line.trim().matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                flushParagraph(doc, para);
                continue;
            }
            if (para.length() > 0) para.append('\n');
            para.append(line);
        }
        flushParagraph(doc, para);
    }

    private record JsonBlock(int endIndex, String prettyJson) {}

    private record JsonSegment(String before, String prettyJson, String after) {}

    private JsonBlock collectJsonBlock(String[] lines, int start) {
        if (lines == null || start < 0 || start >= lines.length) return null;
        String first = lines[start].trim();
        if (!(first.startsWith("{") || first.startsWith("["))) return null;

        StringBuilder raw = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        boolean sawBracket = false;

        for (int row = start; row < lines.length; row++) {
            if (raw.length() > 0) raw.append('\n');
            raw.append(lines[row]);

            String line = lines[row];
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) continue;
                if (c == '{' || c == '[') {
                    depth++;
                    sawBracket = true;
                } else if (c == '}' || c == ']') {
                    depth--;
                }
            }

            if (sawBracket && depth == 0) {
                String pretty = prettyPrintJsonExample(raw.toString());
                if (!pretty.isBlank()) return new JsonBlock(row, pretty);
                return null;
            }
        }
        return null;
    }

    private JsonSegment findInlineJsonSegment(String line) {
        if (line == null || line.isBlank()) return null;
        for (int start = 0; start < line.length(); start++) {
            char first = line.charAt(start);
            if (first != '{' && first != '[') continue;

            int end = findBalancedJsonEnd(line, start);
            if (end <= start) continue;

            String candidate = line.substring(start, end + 1);
            String pretty = prettyPrintJsonExample(candidate);
            if (pretty.isBlank()) continue;

            String before = line.substring(0, start);
            String after = line.substring(end + 1);
            return new JsonSegment(before, pretty, after);
        }
        return null;
    }

    private int findBalancedJsonEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return i;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    private String expandInlineNumberedItems(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text.replace("\r\n", "\n").replace("\r", "\n");
        // 常见 AI 输出会把 "1. xxx；2. yyy；3. zzz" 挤成一段，导出前拆成独立行。
        s = s.replaceAll("([。；;])\\s*(?=\\d{1,2}[\\.、)]\\s*\\S)", "$1\n");
        s = s.replaceAll("([。；;])\\s*(?=（\\d{1,2}）\\s*\\S)", "$1\n");
        s = s.replaceAll("([^\\n])\\s+(?=\\d{1,2}[\\.、)]\\s+\\S)", "$1\n");
        return s;
    }

    private String prettyPrintJsonExample(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.length() < 2) return "";
        boolean fenced = s.startsWith("```json") || s.startsWith("```JSON");
        if (fenced) {
            s = s.replaceFirst("^```(?:json|JSON)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        if (!(s.startsWith("{") && s.endsWith("}")) && !(s.startsWith("[") && s.endsWith("]"))) return "";
        try {
            JsonNode node = objectMapper.readTree(s);
            if (isPrdJsonWrapper(node)) return "";
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isPrdJsonWrapper(JsonNode node) {
        return node != null
                && node.isObject()
                && node.has("chapters")
                && (node.has("title") || node.has("summary"));
    }

    private boolean isMarkdownTableLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (!t.startsWith("|") || !t.endsWith("|") || t.length() < 3) return false;
        int pipes = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '|') pipes++;
        }
        return pipes >= 2;
    }

    private void addMarkdownTable(XWPFDocument doc, List<String> tableLines) {
        if (tableLines == null || tableLines.isEmpty()) return;
        List<String[]> parsedRows = new ArrayList<>();
        for (String line : tableLines) {
            String[] cells = parseMarkdownTableRow(line);
            if (cells.length == 0 || isMarkdownSeparatorRow(cells)) continue;
            parsedRows.add(cells);
        }
        if (parsedRows.isEmpty()) return;

        int colCount = parsedRows.stream().mapToInt(r -> r.length).max().orElse(1);
        String[] headers = padRow(parsedRows.get(0), colCount);
        String[][] rows = new String[Math.max(0, parsedRows.size() - 1)][colCount];
        for (int i = 1; i < parsedRows.size(); i++) {
            rows[i - 1] = padRow(parsedRows.get(i), colCount);
        }
        addTable(doc, headers, rows);
    }

    private String[] parseMarkdownTableRow(String line) {
        String t = line == null ? "" : line.trim();
        t = t.replaceFirst("^\\|", "").replaceFirst("\\|$", "");
        String[] cells = t.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = plainMarkdownCell(cells[i].trim());
        }
        return cells;
    }

    private boolean isMarkdownSeparatorRow(String[] cells) {
        if (cells == null || cells.length == 0) return false;
        for (String cell : cells) {
            if (!cell.trim().matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private String[] padRow(String[] row, int colCount) {
        String[] padded = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            padded[i] = i < row.length ? row[i] : "";
        }
        return padded;
    }

    private String plainMarkdownCell(String cell) {
        if (cell == null) return "";
        return MD_LINK.matcher(cell)
                .replaceAll("$1（$2）")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("`([^`]+)`", "$1");
    }

    private void flushParagraph(XWPFDocument doc, StringBuilder para) {
        if (para.isEmpty()) return;
        addInlineMarkdownParagraph(doc, para.toString().trim());
        para.setLength(0);
    }

    private void addInlineMarkdownParagraph(XWPFDocument doc, String text) {
        if (text == null || text.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(80);
        p.setSpacingBefore(20);
        applyInlineRuns(p, text, 11, false);
    }

    private void addListItem(XWPFDocument doc, String text, boolean ordered) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(40);
        p.setIndentationLeft(360);
        String prefix = ordered ? "• " : "• ";
        // 简化：统一圆点（有序编号已在原文里）
        if (ordered) prefix = "";
        applyInlineRuns(p, prefix + text, 11, false);
    }

    private void addQuote(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
        p.setIndentationLeft(200);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize(10);
        r.setColor("666666");
        r.setItalic(true);
    }

    private void addCodeBlock(XWPFDocument doc, String code) {
        for (String line : code.split("\n", -1)) {
            XWPFParagraph p = doc.createParagraph();
            p.setSpacingAfter(20);
            p.setIndentationLeft(200);
            XWPFRun r = p.createRun();
            r.setText(line);
            r.setFontFamily("Consolas");
            r.setFontSize(9);
            r.setColor("333333");
        }
    }

    /**
     * 解析行内 **bold** `code` [text](url)，写入 runs。
     */
    private void applyInlineRuns(XWPFParagraph p, String text, int fontSize, boolean baseBold) {
        if (text == null) return;
        // 链接先替换为 "text (url)"
        String s = MD_LINK.matcher(text).replaceAll("$1（$2）");

        // 用简单扫描拆分 **bold** 与 `code`
        int i = 0;
        while (i < s.length()) {
            // code
            if (s.charAt(i) == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    XWPFRun r = p.createRun();
                    r.setText(s.substring(i + 1, end));
                    r.setFontFamily("Consolas");
                    r.setFontSize(Math.max(9, fontSize - 1));
                    r.setColor("C7254E");
                    i = end + 1;
                    continue;
                }
            }
            // bold **
            if (i + 1 < s.length() && s.charAt(i) == '*' && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i) {
                    XWPFRun r = p.createRun();
                    r.setText(s.substring(i + 2, end));
                    r.setFontFamily("微软雅黑");
                    r.setFontSize(fontSize);
                    r.setBold(true);
                    i = end + 2;
                    continue;
                }
            }
            // 普通文本直到下一个特殊字符
            int next = nextSpecial(s, i);
            XWPFRun r = p.createRun();
            r.setText(s.substring(i, next));
            r.setFontFamily("微软雅黑");
            r.setFontSize(fontSize);
            r.setBold(baseBold);
            i = next;
        }
    }

    private int nextSpecial(String s, int from) {
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '`' || c == '*') return i;
        }
        return s.length();
    }

    private record Block(boolean code, String lang, String text) {}

    private List<Block> splitBlocks(String src) {
        List<Block> list = new ArrayList<>();
        Matcher m = CODE_FENCE.matcher(src);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                list.add(new Block(false, "", src.substring(last, m.start())));
            }
            list.add(new Block(true, m.group(1) == null ? "" : m.group(1), m.group(2)));
            last = m.end();
        }
        if (last < src.length()) {
            list.add(new Block(false, "", src.substring(last)));
        }
        if (list.isEmpty()) list.add(new Block(false, "", src));
        return list;
    }

    // ==================== 高清插图 ====================

    /**
     * 插入图片：像素分辨率原样嵌入；显示宽度按比例限制在 A4 可用宽内。
     * 高清 PNG（≥1200px）在 Word 中放大到 ~15cm 仍清晰（≈200 DPI）。
     */
    private void addImage(XWPFDocument doc, byte[] imageBytes) {
        try {
            int pictureType = detectPictureType(imageBytes);
            int[] wh = MermaidImageRenderer.imageSize(imageBytes);
            int pxW = wh != null ? wh[0] : 1200;
            int pxH = wh != null ? wh[1] : 800;

            // 以 150 DPI 换算显示尺寸（比 96 DPI 更紧凑且清晰）
            final double dpi = 150.0;
            double widthIn = pxW / dpi;
            double heightIn = pxH / dpi;
            double scale = Math.min(1.0,
                    Math.min(MAX_DISPLAY_INCHES / widthIn, MAX_DISPLAY_HEIGHT_INCHES / heightIn));
            // 过小图放大到至少 4 英寸宽（仍保持像素不拉伸超过 2x 显示）
            if (widthIn * scale < 4.0) {
                scale = Math.min(MAX_DISPLAY_INCHES / widthIn, 4.0 / widthIn);
            }
            widthIn *= scale;
            heightIn *= scale;

            int widthEmu = Units.toEMU(widthIn * 72);   // inches → points → EMU
            int heightEmu = Units.toEMU(heightIn * 72);

            XWPFParagraph p = doc.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            p.setSpacingBefore(160);
            p.setSpacingAfter(160);
            XWPFRun r = p.createRun();
            r.addPicture(new ByteArrayInputStream(imageBytes),
                    pictureType,
                    "chart.png",
                    widthEmu,
                    heightEmu);
            log.debug("插入图片: {}x{}px → {}x{}in", pxW, pxH,
                    String.format("%.2f", widthIn), String.format("%.2f", heightIn));
        } catch (Exception e) {
            log.warn("插入图表失败: {}", e.getMessage());
        }
    }

    private int detectPictureType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return XWPFDocument.PICTURE_TYPE_PNG;
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) return XWPFDocument.PICTURE_TYPE_PNG;
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return XWPFDocument.PICTURE_TYPE_JPEG;
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return XWPFDocument.PICTURE_TYPE_GIF;
        return XWPFDocument.PICTURE_TYPE_PNG;
    }

    // ==================== 章节检索 ====================

    private boolean hasStandardTemplateOutline(JsonNode chapters) {
        return !chapterContentByTitle(chapters, "1.1 需求背景", null).isBlank()
                && !chapterContentByTitle(chapters, "4. 功能需求描述", null).isBlank()
                && !chapterContentByTitle(chapters, "10. 需求评审意见", null).isBlank();
    }

    /** 标准模板骨架覆盖的章节（按归一化后的标题关键字预标记为已消费，避免补充循环重复导出） */
    private void consumeStandardChapters(JsonNode chapters, Set<Integer> consumed) {
        if (chapters == null || !chapters.isArray() || consumed == null) return;
        String[] standardKeywords = {
                "1需求概述", "11需求背景必填", "12需求目标价值必填", "13需求覆盖范围", "14需求列表", "15关联方",
                "2流程图专家评审必备", "3原型图和交互视觉图", "4功能需求描述同行专家评审必备",
                "41耦合场景", "42边界场景", "43非功能需求",
                "5埋点与报表", "6配置项专家评审必备", "7动效专家评审必备", "8运营计划专家评审必备",
                "9安全与合规", "10需求评审意见"
        };
        for (int i = 0; i < chapters.size(); i++) {
            String normalized = normalizeTitleForMatch(textOf(chapters.get(i).path("title")));
            if (normalized.isEmpty()) continue;
            for (String kw : standardKeywords) {
                if (normalized.contains(kw)) {
                    consumed.add(i);
                    break;
                }
            }
        }
    }

    private static String headingOr(String realTitle, String fallback) {
        return (realTitle != null && !realTitle.isBlank()) ? realTitle : fallback;
    }

    private String standardOrFallback(JsonNode chapters, String standardTitleKeyword,
                                      Set<Integer> consumed,
                                      String fallbackTitleKeyword, String... fallbackContentKeywords) {
        String content = chapterContentByTitle(chapters, standardTitleKeyword, consumed);
        if (!content.isBlank()) return content;
        return firstChapterContent(chapters, fallbackTitleKeyword, fallbackContentKeywords);
    }

    private String standardOrDefault(JsonNode chapters, String standardTitleKeyword,
                                     Set<Integer> consumed, String fallback) {
        String content = chapterContentByTitle(chapters, standardTitleKeyword, consumed);
        return content.isBlank() ? fallback : content;
    }

    private String chapterContentByTitle(JsonNode chapters, String titleKeyword, Set<Integer> consumed) {
        if (chapters == null || !chapters.isArray() || titleKeyword == null || titleKeyword.isBlank()) return "";
        String normalizedKeyword = normalizeTitleForMatch(titleKeyword);
        for (int i = 0; i < chapters.size(); i++) {
            JsonNode ch = chapters.get(i);
            String title = textOf(ch.path("title"));
            String normalizedTitle = normalizeTitleForMatch(title);
            if (normalizedTitle.contains(normalizedKeyword)) {
                if (consumed != null) consumed.add(i);
                return textOf(ch.path("content"));
            }
        }
        return "";
    }

    /** 返回匹配章节的实际标题（尊重用户改过的章节名），并把下标标记为已消费 */
    private String chapterTitleByTitle(JsonNode chapters, String titleKeyword, Set<Integer> consumed) {
        if (chapters == null || !chapters.isArray() || titleKeyword == null || titleKeyword.isBlank()) return "";
        String normalizedKeyword = normalizeTitleForMatch(titleKeyword);
        for (int i = 0; i < chapters.size(); i++) {
            JsonNode ch = chapters.get(i);
            String title = textOf(ch.path("title"));
            String normalizedTitle = normalizeTitleForMatch(title);
            if (normalizedTitle.contains(normalizedKeyword)) {
                if (consumed != null) consumed.add(i);
                return title;
            }
        }
        return "";
    }

    private String normalizeTitleForMatch(String title) {
        if (title == null) return "";
        return title.toLowerCase()
                .replaceAll("[\\s#*_`~\\-—–.,，。、:：;；!！?？()（）\\[\\]【】{}<>《》\"“”'‘’|/\\\\]+", "");
    }

    private String firstChapterContent(JsonNode chapters, String titleKeyword, String... contentKeywords) {
        if (chapters == null || !chapters.isArray()) return "（AI未生成此内容）";
        for (JsonNode ch : chapters) {
            String title = textOf(ch.path("title"));
            if (title.contains(titleKeyword)) {
                String content = textOf(ch.path("content"));
                if (!content.isBlank()) {
                    if (contentKeywords.length > 0) {
                        for (String kw : contentKeywords) {
                            int idx = content.indexOf(kw);
                            if (idx >= 0) {
                                // 返回关键词所在段落（到空行或 500 字）
                                int end = content.indexOf("\n\n", idx);
                                if (end < 0) end = Math.min(idx + 500, content.length());
                                return content.substring(Math.max(0, content.lastIndexOf('\n', idx) + 1), end).trim();
                            }
                        }
                    }
                    return content;
                }
            }
        }
        for (JsonNode ch : chapters) {
            String content = textOf(ch.path("content"));
            for (String kw : contentKeywords) {
                if (content.contains(kw)) {
                    int idx = content.indexOf(kw);
                    int end = content.indexOf("\n\n", idx);
                    if (end < 0) end = Math.min(idx + 500, content.length());
                    int start = Math.max(0, content.lastIndexOf('\n', idx) + 1);
                    return content.substring(start, end).trim();
                }
            }
        }
        return "（AI未生成此内容，待补充）";
    }

    private static String typeKey(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase()) {
            case "flow", "structure" -> type.toLowerCase();
            default -> null;
        };
    }

    private byte[] resolveImage(Map<String, byte[]> images, String... keys) {
        if (images == null || images.isEmpty()) return null;
        for (String k : keys) {
            if (k == null) continue;
            byte[] b = images.get(k);
            if (b != null && b.length > 0) return b;
        }
        return null;
    }

    private int headingLevelFromTitle(String title) {
        if (title == null || title.isBlank()) return 1;
        Matcher m = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)").matcher(title);
        if (m.find()) {
            String num = m.group(1);
            int dots = 0;
            for (int i = 0; i < num.length(); i++) if (num.charAt(i) == '.') dots++;
            return Math.min(dots + 1, 3);
        }
        return 1;
    }

    // ==================== POI 基础 ====================

    private void addHeading(XWPFDocument doc, String text, int level) {
        if (text == null || text.isBlank()) return;
        // 防止标题也是 JSON
        String t = cleanText(text);
        if (looksLikeJson(t)) return;
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level <= 1 ? 280 : 160);
        p.setSpacingAfter(100);
        // 使用内置标题样式，便于 Word 大纲导航
        try {
            String style = switch (level) {
                case 0 -> "Title";
                case 1 -> "Heading1";
                case 2 -> "Heading2";
                default -> "Heading3";
            };
            p.setStyle(style);
        } catch (Exception ignored) { /* 部分环境无样式 */ }
        XWPFRun r = p.createRun();
        r.setText(t);
        r.setFontFamily("微软雅黑");
        r.setBold(true);
        int size = switch (level) { case 0 -> 22; case 1 -> 16; case 2 -> 14; default -> 12; };
        r.setFontSize(size);
        if (level == 0) p.setAlignment(ParagraphAlignment.CENTER);
    }

    private void addPlainParagraph(XWPFDocument doc, String text) {
        if (text == null || text.isBlank()) return;
        if (looksLikeJson(text)) return;
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize(11);
    }

    private void addBlank(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(40);
    }

    private void addTable(XWPFDocument doc, String[] headers, String[][] rows) {
        XWPFTable table = doc.createTable(rows.length + 1, headers.length);
        table.setWidth("100%");
        for (int i = 0; i < headers.length; i++)
            setCell(table.getRow(0).getCell(i), headers[i], true, "D9E2F3");
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < headers.length; c++)
                setCell(table.getRow(r + 1).getCell(c), c < rows[r].length ? rows[r][c] : "", false, null);
        addBlank(doc);
    }

    private void setCell(XWPFTableCell cell, String text, boolean bold, String bg) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize(10);
        r.setBold(bold);
        if (bg != null) cell.setColor(bg);
    }
}
