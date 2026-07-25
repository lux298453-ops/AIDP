package com.example.aidocumentplatform.util;

/**
 * JSON 工具方法。
 *
 * 核心：{@link #escapeJsonString} 正确转义 JSON 字符串值中的控制字符。
 * 不要再用手写的 replace("\"", "\\\"") —— 它不处理 \n \r \t 等。
 */
public final class JsonUtils {

    private JsonUtils() {}

    /**
     * 将任意字符串转为安全写入 JSON 值的字面量。
     * 处理：引号、反斜杠、换行、回车、制表、其他控制字符。
     */
    public static String escapeJsonString(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
