package com.example.aidocumentplatform.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Word 文档读取器 —— 用 Apache POI 提取 .docx 文件的结构化纯文本。
 * <p>
 * 会尽量保留标题层级（Heading1/2/3 → # / ## / ###）和表格，
 * 以便作为「自定义 PRD 模板」骨架传给 AI。
 */
@Slf4j
@Component
public class WordReader {

    /**
     * 从 .docx 字节数组中提取带结构标记的文本。
     *
     * @param bytes .docx 文件字节数组
     * @return 提取的文本（标题用 # 标记，表格用 | 分隔）
     */
    public String extractText(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int paragraphCount = 0;
            int tableCount = 0;

            // bodyElements 按文档真实顺序遍历，避免段落/表格错位
            List<IBodyElement> elements = doc.getBodyElements();
            for (IBodyElement el : elements) {
                if (el instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (text == null || text.isBlank()) continue;
                    paragraphCount++;
                    int level = headingLevel(p);
                    if (level > 0) {
                        sb.append("#".repeat(level)).append(' ').append(text.trim()).append('\n');
                    } else {
                        sb.append(text.trim()).append('\n');
                    }
                } else if (el instanceof XWPFTable table) {
                    tableCount++;
                    appendTable(sb, table);
                    sb.append('\n');
                }
            }

            String result = sb.toString().trim();
            log.info("Word 解析完成: {} 段落, {} 表格, {} 字", paragraphCount, tableCount, result.length());
            return result;
        } catch (IOException e) {
            log.error("Word 文件解析失败", e);
            throw new RuntimeException("Word 文件解析失败: " + e.getMessage(), e);
        }
    }

    /** 根据段落样式推断标题层级，非标题返回 0 */
    private int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) {
            // 部分文档用 outline level
            try {
                if (p.getCTP() != null && p.getCTP().getPPr() != null
                        && p.getCTP().getPPr().getOutlineLvl() != null) {
                    int lvl = p.getCTP().getPPr().getOutlineLvl().getVal().intValue();
                    return Math.min(lvl + 1, 3);
                }
            } catch (Exception ignored) { /* fall through */ }
            // 粗体 + 较短行，视作可能的章节标题
            if (isLikelyHeadingByFormat(p)) return 1;
            return 0;
        }
        String s = style.toLowerCase();
        if (s.contains("heading1") || s.equals("1") || s.contains("标题 1") || s.contains("标题1")) return 1;
        if (s.contains("heading2") || s.equals("2") || s.contains("标题 2") || s.contains("标题2")) return 2;
        if (s.contains("heading3") || s.equals("3") || s.contains("标题 3") || s.contains("标题3")) return 3;
        if (s.contains("heading") || s.contains("标题")) return 1;
        return 0;
    }

    private boolean isLikelyHeadingByFormat(XWPFParagraph p) {
        String text = p.getText();
        if (text == null) return false;
        String t = text.trim();
        if (t.length() == 0 || t.length() > 60) return false;
        // 编号开头：1. / 1.1 / 一、
        if (t.matches("^\\d+(\\.\\d+)*[\\.、\\s）.】].*") || t.matches("^[一二三四五六七八九十]+[、.．].*")) {
            return true;
        }
        boolean allBold = true;
        boolean hasRun = false;
        for (XWPFRun r : p.getRuns()) {
            hasRun = true;
            if (r.getText(0) == null || r.getText(0).isBlank()) continue;
            // isBold() 返回原始 boolean，不能与 null 比较
            if (!r.isBold()) { allBold = false; break; }
        }
        return hasRun && allBold;
    }

    private void appendTable(StringBuilder sb, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText() == null ? "" : cell.getText().replace('\n', ' ').trim();
                sb.append(' ').append(cellText).append(" |");
            }
            sb.append('\n');
        }
    }
}
