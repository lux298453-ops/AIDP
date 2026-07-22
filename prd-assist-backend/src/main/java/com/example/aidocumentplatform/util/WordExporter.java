package com.example.aidocumentplatform.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class WordExporter {

    private final PrdContentParser prdContentParser = new PrdContentParser();

    public byte[] export(String title, String description, String contentJson) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            JsonNode root = prdContentParser.normalize(contentJson);
            JsonNode chapters = root.path("chapters");
            String summary = root.path("summary").asText("");

            // ====== 封面 ======
            addHeading(doc, title != null ? title : root.path("title").asText("PRD文档"), 0);
            addParagraph(doc, "生成日期: " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            addParagraph(doc, "");
            addParagraph(doc, "TAPD链接地址：");
            addParagraph(doc, "交互文档地址：");
            addParagraph(doc, "视觉文档地址：");
            addParagraph(doc, "资源切图路径：");
            addParagraph(doc, "");

            // ====== 修订记录 ======
            addHeading(doc, "修订记录", 2);
            addTable(doc, new String[]{"版本号", "修订人", "修订日期", "修订描述"},
                    new String[][]{{"V1.0", "AI", LocalDate.now().toString(), "初始版本"}});
            addParagraph(doc, "");

            // ====== 1. 需求概述 ======
            addHeading(doc, "1. 需求概述", 1);
            if (summary.length() > 5) addParagraph(doc, "一句话需求：" + summary);
            addHeading(doc, "1.1 需求背景（必填）", 2);
            addParagraph(doc, firstChapterContent(chapters, "需求概述", "需求背景"));
            addHeading(doc, "1.2 需求目标/价值（必填）", 2);
            addParagraph(doc, firstChapterContent(chapters, "需求概述", "目标", "价值", "业务价值"));
            addHeading(doc, "1.3 需求覆盖范围", 2);
            addParagraph(doc, firstChapterContent(chapters, "需求概述", "覆盖", "范围", "场景"));
            // 1.4 需求列表
            addHeading(doc, "1.4 需求列表", 2);
            addParagraph(doc, firstChapterContent(chapters, "功能设计", "功能点", "功能列表"));
            // 1.5 关联方
            addHeading(doc, "1.5 关联方", 2);
            addTable(doc, new String[]{"关联方", "关联事项", "对接人"},
                    new String[][]{{"无", "无", "无"}});
            addParagraph(doc, "");

            // ====== 2. 流程图 ======
            addHeading(doc, "2. 流程图（专家评审必备）", 1);
            addParagraph(doc, firstChapterContent(chapters, "交互流程", "流程"));
            addParagraph(doc, "");

            // ====== 3. 原型图 / 交互视觉图 ======
            addHeading(doc, "3. 原型图 和 交互+视觉图", 1);
            addParagraph(doc, firstChapterContent(chapters, "UI设计", "原型", "布局", "视觉", "UI"));
            addParagraph(doc, "");

            // ====== 4. 功能需求描述 ======
            addHeading(doc, "4. 功能需求描述（同行/专家评审必备）", 1);
            addHeading(doc, "名词解释", 2);
            addParagraph(doc, "（没有则填无）");
            addParagraph(doc, "");

            // 输出所有章节为功能点
            if (chapters.isArray() && chapters.size() > 0) {
                int idx = 1;
                for (JsonNode ch : chapters) {
                    String chTitle = ch.path("title").asText("");
                    String chContent = ch.path("content").asText("");
                    if (chContent.isBlank()) continue;
                    addHeading(doc, idx + "）" + chTitle, 3);
                    addParagraph(doc, chContent);
                    addParagraph(doc, "");
                    idx++;
                }
            }

            // 4.x 其他
            addHeading(doc, "4.X 耦合场景", 3);
            addParagraph(doc, firstChapterContent(chapters, "异常处理", "耦合", "异常"));
            addHeading(doc, "4.X 边界场景", 3);
            addParagraph(doc, firstChapterContent(chapters, "异常处理", "边界"));
            addHeading(doc, "4.X 非功能需求", 3);
            addParagraph(doc, firstChapterContent(chapters, "安全与性能", "非功能", "性能"));

            addParagraph(doc, "");

            // ====== 5. 埋点与报表 ======
            addHeading(doc, "5. 埋点与报表", 1);
            addParagraph(doc, "核心关注数据：");
            addParagraph(doc, firstChapterContent(chapters, "安全与性能", "埋点", "指标", "数据统计"));
            addParagraph(doc, "具体埋点文档：需与数分同学对齐并录入obus");
            addParagraph(doc, "报表：（没有则填无）");
            addParagraph(doc, "");

            // ====== 6. 配置项 ======
            addHeading(doc, "6. 配置项（专家评审必备）", 1);
            addParagraph(doc, firstChapterContent(chapters, "安全与性能", "配置"));
            addParagraph(doc, "");

            // ====== 7. 动效 ======
            addHeading(doc, "7. 动效（专家评审必备）", 1);
            addParagraph(doc, "（没有则填无）");
            addParagraph(doc, "");

            // ====== 8. 运营计划 ======
            addHeading(doc, "8. 运营计划（专家评审必备）", 1);
            addParagraph(doc, "（没有则填无）");
            addParagraph(doc, "");

            // ====== 9. 安全与合规 ======
            addHeading(doc, "9. 安全与合规", 1);
            addParagraph(doc, firstChapterContent(chapters, "安全与性能", "安全", "加密", "合规"));
            addParagraph(doc, "");

            // ====== 10. 需求评审意见 ======
            addHeading(doc, "10. 需求评审意见", 1);
            addParagraph(doc, "（必填，没有则填无）");
            addParagraph(doc, "");

            // ====== 附录：原始需求 ======
            if (description != null && !description.isBlank()) {
                addHeading(doc, "附录：用户原始需求输入", 2);
                addParagraph(doc, description);
            }

            doc.write(bos);
            log.info("Word导出成功: title={}, size={}bytes", title, bos.size());
            return bos.toByteArray();

        } catch (IOException e) {
            log.error("Word导出失败", e);
            throw new RuntimeException("Word导出失败: " + e.getMessage(), e);
        }
    }

    /** 从章节中查找匹配关键词的内容并返回，找不到返回默认值 */
    private String firstChapterContent(JsonNode chapters, String titleKeyword, String... contentKeywords) {
        if (chapters == null || !chapters.isArray()) return "（AI未生成此内容）";
        // 先按标题找
        for (JsonNode ch : chapters) {
            if (ch.path("title").asText("").contains(titleKeyword)) {
                String content = ch.path("content").asText("");
                if (!content.isBlank()) {
                    // 如果有额外关键词，尝试提取对应段落
                    if (contentKeywords.length > 0) {
                        for (String kw : contentKeywords) {
                            int idx = content.indexOf(kw);
                            if (idx >= 0) {
                                // 返回关键词所在的段落
                                int end = content.indexOf('\n', idx);
                                if (end < 0) end = Math.min(idx + 300, content.length());
                                return content.substring(Math.max(0, idx - 10), end).trim();
                            }
                        }
                    }
                    return content;
                }
            }
        }
        // 没找到，遍历所有章节搜索内容关键词
        for (JsonNode ch : chapters) {
            String content = ch.path("content").asText("");
            for (String kw : contentKeywords) {
                if (content.contains(kw)) {
                    int idx = content.indexOf(kw);
                    int end = content.indexOf('\n', idx);
                    if (end < 0) end = Math.min(idx + 300, content.length());
                    // 返回包含关键词的段落
                    int start = Math.max(0, content.lastIndexOf('\n', idx) + 1);
                    return content.substring(start, end).trim();
                }
            }
        }
        return "（AI未生成此内容，待补充）";
    }

    // ==================== POI 工具方法 ====================

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level <= 2 ? 240 : 120);
        p.setSpacingAfter(100);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setBold(true);
        int size = switch (level) { case 0 -> 22; case 1 -> 16; case 2 -> 14; default -> 12; };
        r.setFontSize(size);
        if (level == 0) p.setAlignment(ParagraphAlignment.CENTER);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        if (text == null || text.isBlank()) return;
        for (String line : text.replace("\\n", "\n").split("\n")) {
            XWPFParagraph p = doc.createParagraph();
            p.setSpacingAfter(60);
            XWPFRun r = p.createRun();
            r.setText(line.trim());
            r.setFontFamily("微软雅黑");
            r.setFontSize(11);
        }
    }

    private void addTable(XWPFDocument doc, String[] headers, String[][] rows) {
        XWPFTable table = doc.createTable(rows.length + 1, headers.length);
        table.setWidth("100%");
        for (int i = 0; i < headers.length; i++)
            setCell(table.getRow(0).getCell(i), headers[i], true, "D9E2F3");
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < headers.length; c++)
                setCell(table.getRow(r + 1).getCell(c), c < rows[r].length ? rows[r][c] : "", false, null);
        addParagraph(doc, "");
    }

    private void setCell(XWPFTableCell cell, String text, boolean bold, String bg) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(text); r.setFontFamily("微软雅黑"); r.setFontSize(10); r.setBold(bold);
        if (bg != null) cell.setColor(bg);
    }
}
