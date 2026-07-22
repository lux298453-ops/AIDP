package com.example.aidocumentplatform.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Word 文档读取器 —— 用 Apache POI 提取 .docx 文件的纯文本内容。
 */
@Slf4j
@Component
public class WordReader {

    /**
     * 从 .docx 字节数组中提取纯文本。
     *
     * @param bytes .docx 文件字节数组
     * @return 提取的文本内容（段落间用换行分隔）
     */
    public String extractText(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            String result = sb.toString().trim();
            log.info("Word 解析完成: {} 个段落, {} 字", doc.getParagraphs().size(), result.length());
            return result;
        } catch (IOException e) {
            log.error("Word 文件解析失败", e);
            throw new RuntimeException("Word 文件解析失败: " + e.getMessage(), e);
        }
    }
}
