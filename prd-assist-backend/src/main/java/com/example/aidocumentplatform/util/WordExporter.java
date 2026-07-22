package com.example.aidocumentplatform.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Word 文档导出器。
 *
 * 将 prd_document.content（结构化 JSON）按 PRD 模板格式渲染为 .docx：
 *   { title, summary, chapters: [{title, content}, ...] }
 */
@Slf4j
@Component
public class WordExporter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 导出 .docx 文件字节数组 */
    public byte[] export(String title, String description, String contentJson) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            JsonNode root = parseJson(contentJson);
            JsonNode chapters = root.path("chapters");
            String summary = root.path("summary").asText(description != null ? description : "");

            // ====== 封面标题 ======
            addHeading(doc, title != null ? title : root.path("title").asText("PRD文档"), 0);
            addParagraph(doc, "生成日期: " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            addParagraph(doc, "");

            // ====== 修订记录表 ======
            addHeading(doc, "修订记录", 2);
            addTable(doc, new String[]{"版本号", "修订人", "修订日期", "修订描述"},
                    new String[][]{{"V1.0", "AI", LocalDate.now().toString(), "初始版本"}});
            addParagraph(doc, "");

            // ====== 1. 需求概述 ======
            addHeading(doc, "1. 需求概述", 1);
            addHeading(doc, "1.1 需求背景", 2);
            addParagraph(doc, summary.isEmpty() ? "（待补充）" : summary);

            addHeading(doc, "1.2 需求目标/价值", 2);
            String goal = findChapterContent(chapters, "需求分析", "需求目标");
            addParagraph(doc, !goal.isEmpty() ? goal : "（待补充，可量化指标）");

            addHeading(doc, "1.3 需求覆盖范围", 2);
            addParagraph(doc, "本PRD涵盖以下功能模块，详见功能需求描述章节。");

            addHeading(doc, "1.4 需求列表", 2);
            addTable(doc, new String[]{"功能模块", "功能点", "优先级", "功能简述"},
                    buildFeatureRows(chapters));

            addHeading(doc, "1.5 关联方", 2);
            addTable(doc, new String[]{"关联方", "关联事项", "对接人"},
                    new String[][]{{"无", "无", "无"}});
            addParagraph(doc, "");

            // ====== 2. 流程图 ======
            addHeading(doc, "2. 流程图", 1);
            addParagraph(doc, "（待补充 — 建议使用流程图工具绘制后粘贴）");
            addParagraph(doc, "");

            // ====== 3. 原型图 / 交互视觉图 ======
            addHeading(doc, "3. 原型图 和 交互+视觉图", 1);
            addParagraph(doc, "（待补充 — 原型图可通过平台「原型生成」模块生成）");
            addParagraph(doc, "");

            // ====== 4. 功能需求描述（核心：输出所有章节内容） ======
            addHeading(doc, "4. 功能需求描述", 1);
            addHeading(doc, "名词解释", 2);
            addParagraph(doc, "（没有则填无或删掉）");
            addParagraph(doc, "");

            int featureIdx = 1;
            for (JsonNode ch : chapters) {
                String chTitle = ch.path("title").asText("");
                String chContent = ch.path("content").asText("");
                if (chContent.isBlank()) continue;
                addHeading(doc, String.format("%d) %s", featureIdx++, chTitle), 3);
                addParagraph(doc, chContent);
                addParagraph(doc, "");
            }

            addHeading(doc, String.format("%d) 其他", featureIdx), 3);
            addHeading(doc, "耦合场景", 4);
            addParagraph(doc, "需考虑与商业化需求的耦合；弱网、无网络、杀进程等边界场景。");
            addHeading(doc, "边界场景", 4);
            addParagraph(doc, "弱网、无网络、返回、杀进程、多次点击等。");
            addHeading(doc, "非功能需求", 4);
            addParagraph(doc, "（例如性能要求：页面加载不得超过XX毫秒）");
            addParagraph(doc, "");

            // ====== 5~10 ======
            addHeading(doc, "5. 埋点与报表", 1);
            addParagraph(doc, "核心关注数据：（结果导向梳理，便于BI/开发理解需求）");
            addParagraph(doc, "具体埋点文档：需与数分同学对齐并录入obus");
            addParagraph(doc, "报表：（没有则填无）");
            addParagraph(doc, "");

            addHeading(doc, "6. 配置项", 1);
            addParagraph(doc, "（没有则填无）");
            addParagraph(doc, "");

            addHeading(doc, "7. 动效", 1);
            addParagraph(doc, "（没有则填无）");
            addParagraph(doc, "");

            addHeading(doc, "8. 运营计划", 1);
            addParagraph(doc, "（没有则填无）");
            addParagraph(doc, "");

            addHeading(doc, "9. 安全与合规", 1);
            String security = findChapterContent(chapters, "技术需求");
            addParagraph(doc, !security.isEmpty() ? security : "（没有则填无）");
            addParagraph(doc, "");

            addHeading(doc, "10. 需求评审意见", 1);
            addParagraph(doc, "（必填，没有则填无）");
            addParagraph(doc, "");

            doc.write(bos);
            log.info("Word导出成功: title={}, size={}bytes", title, bos.size());
            return bos.toByteArray();

        } catch (IOException e) {
            log.error("Word导出失败", e);
            throw new RuntimeException("Word导出失败: " + e.getMessage(), e);
        }
    }

    // ==================== POI 工具方法 ====================

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level <= 2 ? 240 : 160);
        p.setSpacingAfter(120);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setBold(true);
        int size = switch (level) { case 0 -> 22; case 1 -> 16; case 2 -> 14; default -> 12; };
        r.setFontSize(size);
        if (level == 0) p.setAlignment(ParagraphAlignment.CENTER);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(80);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize(11);
    }

    private void addTable(XWPFDocument doc, String[] headers, String[][] rows) {
        XWPFTable table = doc.createTable(rows.length + 1, headers.length);
        table.setWidth("100%");
        // header row
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = table.getRow(0).getCell(i);
            cell.setColor("D9E2F3");
            setCellText(cell, headers[i], true);
        }
        // data rows
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < headers.length; c++) {
                setCellText(table.getRow(r + 1).getCell(c),
                        c < rows[r].length ? rows[r][c] : "", false);
            }
        }
        addParagraph(doc, "");
    }

    private void setCellText(XWPFTableCell cell, String text, boolean bold) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize(10);
        r.setBold(bold);
    }

    // ==================== JSON 解析 ====================

    private JsonNode parseJson(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { return objectMapper.createObjectNode(); }
    }

    private String findChapterContent(JsonNode chapters, String... keywords) {
        for (JsonNode ch : chapters) {
            String title = ch.path("title").asText("");
            for (String kw : keywords) if (title.contains(kw)) return ch.path("content").asText("");
        }
        return "";
    }

    private String[][] buildFeatureRows(JsonNode chapters) {
        if (!chapters.isArray() || chapters.isEmpty()) return new String[][]{{"待补充", "—", "P0", "—"}};
        String[][] rows = new String[chapters.size()][4];
        for (int i = 0; i < chapters.size(); i++) {
            String title = chapters.get(i).path("title").asText("功能点");
            rows[i] = new String[]{title, title, "P0", chapters.get(i).path("content").asText("").substring(0, Math.min(80, chapters.get(i).path("content").asText("").length()))};
        }
        return rows;
    }
}
