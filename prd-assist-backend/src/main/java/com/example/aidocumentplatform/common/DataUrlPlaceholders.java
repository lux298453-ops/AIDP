package com.example.aidocumentplatform.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 HTML 中体积巨大的内嵌 base64 Data URL 替换成短占位符。
 *
 * 原型 HTML 为了单文件预览会内嵌整张图片（单张可达数 MB），
 * 直接交给大模型会超出上下文窗口。送模型前用 {@link #compress} 摘出图片，
 * 模型返回后用 {@link #restore} 原样还原。
 */
public final class DataUrlPlaceholders {

    /** 低于该长度的 Data URL（小图标等）不值得替换，直接保留。 */
    private static final int MIN_BASE64_LENGTH = 200;

    private static final Pattern DATA_URL = Pattern.compile(
            "data:[\\w.+-]+/[\\w.+-]+;base64,[A-Za-z0-9+/=]{" + MIN_BASE64_LENGTH + ",}");

    private DataUrlPlaceholders() {
    }

    public record Compressed(String text, Map<String, String> placeholders) {
    }

    public static Compressed compress(String html) {
        if (html == null || html.isBlank()) {
            return new Compressed(html == null ? "" : html, Map.of());
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        Map<String, String> keyByDataUrl = new LinkedHashMap<>();
        Matcher matcher = DATA_URL.matcher(html);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String dataUrl = matcher.group();
            String key = keyByDataUrl.computeIfAbsent(dataUrl, url -> {
                String next = "__PROTO_IMG_" + (keyByDataUrl.size() + 1) + "__";
                placeholders.put(next, url);
                return next;
            });
            matcher.appendReplacement(out, Matcher.quoteReplacement(key));
        }
        matcher.appendTail(out);
        return new Compressed(out.toString(), Map.copyOf(placeholders));
    }

    public static String restore(String text, Map<String, String> placeholders) {
        if (text == null || text.isEmpty()) return "";
        if (placeholders == null || placeholders.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
