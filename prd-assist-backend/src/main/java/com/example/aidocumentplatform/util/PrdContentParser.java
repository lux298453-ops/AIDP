package com.example.aidocumentplatform.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Parses the JSON object returned by the AI model, with tolerance for common formatting issues. */
@Slf4j
@Component
public class PrdContentParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String normalizeToJson(String raw) {
        return normalize(raw).toString();
    }

    public JsonNode normalize(String raw) {
        return parse(raw, true);
    }

    public JsonNode parseObject(String raw) {
        return parse(raw, false);
    }

    private JsonNode parse(String raw, boolean requireChapters) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI 返回内容为空");
        }

        String working = raw.trim();

        // === Phase 1: Strip markdown fences ===
        working = stripMarkdownFence(working);

        // === Phase 2: Try direct parse (up to 3 unwrapping attempts) ===
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode node = tryRead(working);
            if (node != null) {
                if (node.isTextual()) {
                    working = stripMarkdownFence(node.asText().trim());
                    continue;
                }
                return validateOrRepair(node, requireChapters);
            }
            // Try to extract JSON object from surrounding text
            String extracted = extractJson(working);
            if (!extracted.equals(working)) {
                working = extracted;
                continue;
            }
            // Try to unescape legacy double-encoded strings
            String unescaped = unescapeLegacy(working);
            if (!unescaped.equals(working)) {
                working = unescaped;
                continue;
            }
            break;
        }

        // === Phase 3: Try to repair truncated JSON ===
        JsonNode repaired = tryRepairTruncatedJson(working);
        if (repaired != null) {
            log.warn("PRD JSON 被截断，已自动修复");
            return validateOrRepair(repaired, requireChapters);
        }

        // === Phase 4: Fallback — wrap as minimal PRD structure ===
        log.warn("无法解析 AI 返回为合法 JSON，使用 fallback 包装。原始内容前 500 字符: {}",
                raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.put("title", "PRD 文档");
        fallback.put("summary", "AI 返回了非标准格式，原始内容已保留在正文中");
        ArrayNode chapters = fallback.putArray("chapters");
        ObjectNode ch = chapters.addObject();
        ch.put("title", "完整输出");
        ch.put("content", raw.length() > 8000 ? raw.substring(0, 8000) : raw);
        return fallback;
    }

    // ==================== JSON extraction helpers ====================

    private JsonNode tryRead(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Extract the first JSON object `{...}` from text that may contain extra content. */
    private String extractJson(String value) {
        int start = value.indexOf('{');
        if (start < 0) return value;
        // Find matching closing brace
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return value.substring(start, i + 1);
            }
        }
        return value.substring(start); // unclosed — return everything from `{` onwards
    }

    /** Attempt to repair truncated JSON by closing unclosed braces/brackets. */
    private JsonNode tryRepairTruncatedJson(String value) {
        if (value == null || value.isBlank()) return null;
        String s = value.trim();
        // Remove trailing incomplete strings like `"cha`
        int lastQuote = s.lastIndexOf('"');
        if (lastQuote > 0) {
            // Count quotes — if odd, the last string is incomplete
            int quoteCount = 0;
            boolean escaped = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') quoteCount++;
            }
            if (quoteCount % 2 != 0) {
                // Last string is incomplete — remove it and everything after
                s = s.substring(0, lastQuote);
                // Also remove trailing comma/colon
                s = s.replaceFirst("[,:]\\s*$", "");
            }
        }
        // Count unclosed braces and brackets
        int braceDepth = 0, bracketDepth = 0;
        boolean inString = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') braceDepth++;
            else if (c == '}') braceDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;
        }
        if (braceDepth <= 0 && bracketDepth <= 0) return null; // not truncated

        StringBuilder sb = new StringBuilder(s);
        // Close brackets first, then braces
        for (int i = 0; i < bracketDepth; i++) sb.append(']');
        for (int i = 0; i < braceDepth; i++) sb.append('}');

        JsonNode node = tryRead(sb.toString());
        if (node != null) return node;

        // Also try: if it ends with a comma, remove trailing comma first
        if (s.endsWith(",")) {
            s = s.substring(0, s.length() - 1);
            sb = new StringBuilder(s);
            for (int i = 0; i < bracketDepth; i++) sb.append(']');
            for (int i = 0; i < braceDepth; i++) sb.append('}');
            node = tryRead(sb.toString());
            if (node != null) return node;
        }

        return null;
    }

    /** Validate structure and optionally repair missing `chapters`. */
    private JsonNode validateOrRepair(JsonNode node, boolean requireChapters) {
        if (!node.isObject()) {
            // If it's an array, try first element
            if (node.isArray() && node.size() > 0) {
                return validateOrRepair(node.get(0), requireChapters);
            }
            throw new IllegalArgumentException("AI 返回内容必须是 JSON 对象");
        }
        if (requireChapters && !node.has("chapters")) {
            // Auto-repair: wrap content in a single chapter
            log.warn("PRD JSON 缺少 chapters 字段，自动包装为单章节");
            ObjectNode obj = node.deepCopy();
            ArrayNode chapters = obj.putArray("chapters");
            ObjectNode ch = chapters.addObject();
            ch.put("title", "正文");
            // Use existing content fields if available — never dump raw JSON via toString()
            StringBuilder content = new StringBuilder();
            if (obj.has("summary") && obj.get("summary").isValueNode()) {
                content.append(obj.get("summary").asText()).append("\n\n");
            }
            if (obj.has("content")) {
                JsonNode c = obj.get("content");
                content.append(c.isValueNode() ? c.asText() : extractReadableText(c));
            }
            if (obj.has("text") && obj.get("text").isValueNode()) {
                content.append(obj.get("text").asText());
            }
            if (content.isEmpty()) {
                content.append(extractReadableText(obj));
            }
            ch.put("content", content.toString().trim());
            return obj;
        }
        return node;
    }

    // ==================== Utility methods ====================

    /**
     * 从任意 JsonNode 提取可读纯文本，避免把 JSON 结构写进文档。
     */
    private String extractReadableText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isValueNode()) return node.asText("");
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String t = extractReadableText(item);
                if (t.isBlank()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.startsWith("-") || t.startsWith("•") ? t : "• " + t);
            }
            return sb.toString();
        }
        // object: 优先 content/text/title/summary
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"title", "summary", "content", "text", "markdown", "description"}) {
            if (node.has(key)) {
                String t = extractReadableText(node.get(key));
                if (t.isBlank()) continue;
                if (sb.length() > 0) sb.append('\n');
                if ("title".equals(key) || "summary".equals(key)) {
                    sb.append(t);
                } else {
                    sb.append(t);
                }
            }
        }
        if (sb.length() > 0) return sb.toString().trim();
        // 其余字段
        node.fields().forEachRemaining(e -> {
            if ("type".equals(e.getKey()) || "id".equals(e.getKey()) || "chapters".equals(e.getKey())) return;
            String t = extractReadableText(e.getValue());
            if (t.isBlank()) return;
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append("：").append(t);
        });
        return sb.toString().trim();
    }

    private String stripMarkdownFence(String value) {
        String result = value.trim();
        // Remove opening fence: ```json or ``` or just ```
        if (result.startsWith("```")) {
            result = result.replaceFirst("^```(?:json)?\\s*", "");
            result = result.replaceFirst("\\s*```$", "");
        }
        return result.trim();
    }

    private String unescapeLegacy(String value) {
        if (!value.contains("\\n") && !value.contains("\\\\n")
                && !value.contains("\\\"") && !value.contains("\\\\\"")) return value;
        return value.replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"");
    }
}
