package com.example.aidocumentplatform.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 将审查报告（ReviewReport.issues JSON）导出为 Word 文档。
 */
@Slf4j
@Component
public class ReviewWordExporter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] export(String issuesJson, String dimensionsJson) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            JsonNode root = parseIssues(issuesJson);
            int score = root.path("score").asInt(0);
            String summary = root.path("summary").asText("无概述");
            JsonNode issues = root.path("issues");

            // ====== 标题 ======
            XWPFParagraph titleP = doc.createParagraph();
            titleP.setAlignment(ParagraphAlignment.CENTER);
            titleP.setSpacingAfter(200);
            XWPFRun titleR = titleP.createRun();
            titleR.setText("PRD 审查报告");
            titleR.setFontFamily("微软雅黑");
            titleR.setBold(true);
            titleR.setFontSize(22);
            titleR.setColor("1a1a2e");

            // ====== 基本信息 ======
            addInfoTable(doc, score, summary);

            // ====== 审查维度 ======
            addHeading(doc, "审查维度", 1);
            try {
                JsonNode dims = objectMapper.readTree(dimensionsJson);
                if (dims.isArray()) {
                    for (JsonNode d : dims) {
                        addParagraph(doc, "• " + d.asText());
                    }
                }
            } catch (Exception e) {
                addParagraph(doc, "• " + (dimensionsJson != null ? dimensionsJson : "未指定"));
            }
            addParagraph(doc, "");

            // ====== 问题明细 ======
            addHeading(doc, "问题明细", 1);
            if (issues.isArray() && issues.size() > 0) {
                // 按严重程度排序
                java.util.ArrayList<JsonNode> sortedIssues = new java.util.ArrayList<>();
                issues.forEach(sortedIssues::add);
                java.util.Map<String, Integer> severityOrder = java.util.Map.of(
                        "CRITICAL", 0, "MAJOR", 1, "MINOR", 2, "SUGGESTION", 3);
                sortedIssues.sort((a, b) -> {
                    int sa = severityOrder.getOrDefault(a.path("severity").asText(""), 9);
                    int sb = severityOrder.getOrDefault(b.path("severity").asText(""), 9);
                    return Integer.compare(sa, sb);
                });

                int idx = 1;
                for (JsonNode issue : sortedIssues) {
                    String severity = issue.path("severity").asText("未知");
                    String dimension = issue.path("dimension").asText("未分类");
                    String location = issue.path("location").asText("未指定位置");
                    String description = issue.path("description").asText("无描述");
                    String suggestion = issue.path("suggestion").asText("无建议");

                    String sevLabel = switch (severity) {
                        case "CRITICAL" -> "【严重】";
                        case "MAJOR" -> "【重要】";
                        case "MINOR" -> "【轻微】";
                        case "SUGGESTION" -> "【建议】";
                        default -> "【" + severity + "】";
                    };

                    addHeading(doc, idx + ". " + sevLabel + " " + location, 2);
                    addLabeledParagraph(doc, "严重程度", severity);
                    addLabeledParagraph(doc, "审查维度", dimension);
                    addLabeledParagraph(doc, "问题描述", description);
                    addLabeledParagraph(doc, "修复建议", suggestion);
                    addParagraph(doc, "");
                    idx++;
                }
            } else {
                addParagraph(doc, "未发现问题，文档质量良好。");
            }

            // ====== 页脚 ======
            addParagraph(doc, "");
            XWPFParagraph footerP = doc.createParagraph();
            footerP.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun footerR = footerP.createRun();
            footerR.setText("报告生成时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            footerR.setFontFamily("微软雅黑");
            footerR.setFontSize(9);
            footerR.setColor("999999");

            doc.write(bos);
            log.info("审查报告Word导出成功: score={}, issues={}", score, issues.size());
            return bos.toByteArray();

        } catch (IOException e) {
            log.error("审查报告Word导出失败", e);
            throw new RuntimeException("审查报告导出失败: " + e.getMessage(), e);
        }
    }

    private JsonNode parseIssues(String issuesJson) {
        if (issuesJson == null || issuesJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(issuesJson);
        } catch (Exception e) {
            // 可能是多层 JSON 字符串嵌套，尝试解开
            String s = issuesJson.trim();
            for (int i = 0; i < 3; i++) {
                try {
                    JsonNode node = objectMapper.readTree(s);
                    if (node.isTextual()) {
                        s = node.asText();
                        continue;
                    }
                    return node;
                } catch (Exception ignored) {
                    break;
                }
            }
            return objectMapper.createObjectNode();
        }
    }

    // ==================== Word 构建工具方法 ====================

    private void addInfoTable(XWPFDocument doc, int score, String summary) {
        XWPFTable table = doc.createTable(1, 2);
        table.setWidth("100%");

        // 左列：评分
        XWPFTableCell scoreCell = table.getRow(0).getCell(0);
        scoreCell.setWidth("30%");
        scoreCell.removeParagraph(0);
        XWPFParagraph sp = scoreCell.addParagraph();
        sp.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun sr = sp.createRun();
        sr.setText(String.valueOf(score));
        sr.setFontFamily("微软雅黑");
        sr.setBold(true);
        sr.setFontSize(48);
        String scoreColor = score >= 80 ? "67c23a" : (score >= 60 ? "e6a23c" : "f56c6c");
        sr.setColor(scoreColor);
        XWPFRun sr2 = sp.createRun();
        sr2.setText(" 分");
        sr2.setFontFamily("微软雅黑");
        sr2.setFontSize(14);
        sr2.setColor("999999");

        // 右列：概述
        XWPFTableCell summaryCell = table.getRow(0).getCell(1);
        summaryCell.removeParagraph(0);
        XWPFParagraph smp = summaryCell.addParagraph();
        XWPFRun smr = smp.createRun();
        smr.setText("审查概述");
        smr.setFontFamily("微软雅黑");
        smr.setBold(true);
        smr.setFontSize(14);
        XWPFParagraph smp2 = summaryCell.addParagraph();
        XWPFRun smr2 = smp2.createRun();
        smr2.setText(summary);
        smr2.setFontFamily("微软雅黑");
        smr2.setFontSize(11);

        addParagraph(doc, "");
    }

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level <= 2 ? 200 : 120);
        p.setSpacingAfter(80);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setBold(true);
        int size = switch (level) { case 1 -> 16; case 2 -> 14; default -> 12; };
        r.setFontSize(size);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        if (text == null || text.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize(11);
    }

    private void addLabeledParagraph(XWPFDocument doc, String label, String text) {
        if (text == null || text.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(40);
        XWPFRun labelRun = p.createRun();
        labelRun.setText(label + "：");
        labelRun.setFontFamily("微软雅黑");
        labelRun.setBold(true);
        labelRun.setFontSize(11);
        labelRun.setColor("606266");
        XWPFRun textRun = p.createRun();
        textRun.setText(text);
        textRun.setFontFamily("微软雅黑");
        textRun.setFontSize(11);
    }
}
