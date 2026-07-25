package com.example.aidocumentplatform.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

/**
 * Mermaid / 图片 URL → 高清 PNG。
 *
 * <ul>
 *   <li>Kroki 优先，mermaid.ink 兜底</li>
 *   <li>渲染前注入较大字号/间距，保证像素宽度 ≥ 1200</li>
 *   <li>内存缓存（按源码 hash），避免同文档多章节重复渲染</li>
 * </ul>
 */
@Slf4j
@Component
public class MermaidImageRenderer {

    public static final int MIN_EXPORT_WIDTH = 1200;

    private static final Pattern MERMAID_FENCE = Pattern.compile(
            "```mermaid[ \\t]*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final Pattern MD_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");

    private static final Pattern RAW_URL = Pattern.compile(
            "(?i)(structureDiagramUrl|flowDiagramUrl|diagramUrl|imageUrl)\\s*[:=]\\s*['\"]?(https?://\\S+)");

    private static final String HQ_INIT = """
            %%{init: {
              'theme': 'base',
              'themeVariables': {
                'fontSize': '18px',
                'fontFamily': 'Microsoft YaHei, Arial, sans-serif',
                'primaryColor': '#e8f0fe',
                'primaryTextColor': '#1a1a1a',
                'primaryBorderColor': '#5b7cfa',
                'lineColor': '#555555',
                'secondaryColor': '#f5f5f5',
                'tertiaryColor': '#ffffff'
              },
              'flowchart': { 'htmlLabels': false, 'curve': 'basis', 'nodeSpacing': 40, 'rankSpacing': 50, 'padding': 16 },
              'sequence': { 'actorMargin': 50, 'messageMargin': 40 }
            }}%%
            """;

    private final WebClient webClient;
    private final String krokiBaseUrl;
    private final boolean enabled;
    /** mermaid 源码 hash → PNG */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 64;

    public MermaidImageRenderer(
            WebClient webClient,
            @Value("${app.mermaid.kroki-url:https://kroki.io}") String krokiBaseUrl,
            @Value("${app.mermaid.enabled:true}") boolean enabled) {
        this.webClient = webClient.mutate()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(12 * 1024 * 1024))
                .build();
        this.krokiBaseUrl = krokiBaseUrl.endsWith("/")
                ? krokiBaseUrl.substring(0, krokiBaseUrl.length() - 1)
                : krokiBaseUrl;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 从章节 content 提取第一段 mermaid 源码 */
    public String extractMermaid(String content) {
        if (content == null || content.isBlank()) return null;
        String text = content.replace("\\n", "\n").replace("\r\n", "\n");
        Matcher m = MERMAID_FENCE.matcher(text);
        if (m.find()) {
            String code = m.group(1).trim();
            return code.isEmpty() ? null : code;
        }
        String[] lines = text.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().matches("(?i)^(flowchart|graph|sequencediagram)\\b.*")) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder buf = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String t = lines[i].trim();
            if (i > start && !t.isEmpty()
                    && !t.matches("(?i).*(--|==>|-->|\\[|\\]|subgraph|participant|Note|style |classDef).*")
                    && !t.matches("(?i)^(end|subgraph|participant)\\b.*")
                    && !t.matches("^[A-Za-z][\\w]*([\\[{(].*)?$")
                    && t.matches(".*[\\u4e00-\\u9fff].*")
                    && buf.length() > 20) {
                break;
            }
            buf.append(lines[i]).append('\n');
        }
        String code = buf.toString().trim();
        return code.isEmpty() ? null : code;
    }

    public boolean hasRenderableChart(String content) {
        if (content == null || content.isBlank()) return false;
        if (extractMermaid(content) != null) return true;
        return extractFirstImageUrl(content) != null;
    }

    public String extractFirstImageUrl(String content) {
        if (content == null || content.isBlank()) return null;
        String text = content.replace("\\n", "\n");
        Matcher md = MD_IMAGE.matcher(text);
        if (md.find()) return md.group(1).trim();
        Matcher raw = RAW_URL.matcher(text);
        if (raw.find()) return raw.group(2).replaceAll("[,;\"']+$", "").trim();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.matches("(?i)^https?://\\S+\\.(png|jpe?g|gif|webp|svg)(\\?\\S*)?$")) {
                return t;
            }
        }
        return null;
    }

    /**
     * 渲染 mermaid → 高清 PNG（宽 ≥ {@link #MIN_EXPORT_WIDTH}，带缓存）。
     */
    public byte[] renderMermaidToPng(String mermaidCode) {
        if (!enabled || mermaidCode == null || mermaidCode.isBlank()) return null;
        String code = sanitizeMermaidCode(mermaidCode);
        if (code.isBlank()) return null;
        String key = sha1(code);
        byte[] cached = cache.get(key);
        if (cached != null) return cached;

        String hq = ensureHqInit(code);
        byte[] png = renderViaKroki(hq);
        if (png == null) png = renderViaMermaidInk(hq);
        if (png == null && !hq.equals(code)) {
            png = renderViaKroki(code);
            if (png == null) png = renderViaMermaidInk(code);
        }
        if (png == null) {
            log.warn("Mermaid remote rendering failed, using local fallback image. sourceLen={}, source={}",
                    code.length(), code.substring(0, Math.min(300, code.length())).replace('\n', ' '));
            png = createFallbackImage(code);
        }
        if (png == null) return null;

        png = ensureMinWidth(png, MIN_EXPORT_WIDTH);
        putCache(key, png);
        return png;
    }

    public byte[] downloadImage(String url) {
        if (!enabled || url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                log.warn("拒绝非 HTTP 图片 URL: {}", url);
                return null;
            }
            byte[] bytes = webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.ALL)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(20))
                    .block();
            if (bytes == null || bytes.length < 32) return null;
            if (bytes.length > 8 * 1024 * 1024) {
                log.warn("图片过大，跳过: url={}, size={}", url, bytes.length);
                return null;
            }
            return ensureMinWidth(bytes, MIN_EXPORT_WIDTH);
        } catch (Exception e) {
            log.warn("图片下载失败: {} — {}", url, e.getMessage());
            return null;
        }
    }

    public byte[] resolveChartPng(String content) {
        String mermaid = extractMermaid(content);
        if (mermaid != null) {
            byte[] png = renderMermaidToPng(mermaid);
            if (png != null) return png;
        }
        String imgUrl = extractFirstImageUrl(content);
        if (imgUrl != null) return downloadImage(imgUrl);
        return null;
    }

    /** 读取 PNG/JPEG 像素宽；失败返回 0 */
    public static int imageWidth(byte[] bytes) {
        int[] wh = imageSize(bytes);
        return wh == null ? 0 : wh[0];
    }

    public static int[] imageSize(byte[] bytes) {
        if (bytes == null || bytes.length < 24) return null;
        try {
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) {
                int w = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16)
                        | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
                int h = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16)
                        | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
                if (w > 0 && h > 0) return new int[]{w, h};
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                int i = 2;
                while (i + 9 < bytes.length) {
                    if ((bytes[i] & 0xFF) != 0xFF) { i++; continue; }
                    int marker = bytes[i + 1] & 0xFF;
                    if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                        int h = ((bytes[i + 5] & 0xFF) << 8) | (bytes[i + 6] & 0xFF);
                        int w = ((bytes[i + 7] & 0xFF) << 8) | (bytes[i + 8] & 0xFF);
                        if (w > 0 && h > 0) return new int[]{w, h};
                        break;
                    }
                    if (marker == 0xD8 || marker == 0xD9) { i += 2; continue; }
                    int len = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
                    i += 2 + len;
                }
            }
        } catch (Exception ignored) { /* fallthrough */ }
        return null;
    }

    // ── private ──────────────────────────────────────────────

    private String ensureHqInit(String code) {
        String c = code.trim();
        if (c.contains("%%{init:")) return c;
        return HQ_INIT.trim() + "\n" + c;
    }

    private String sanitizeMermaidCode(String raw) {
        String c = raw == null ? "" : raw.trim();
        c = c.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\\n", "\n")
                .replace("\uFEFF", "");
        c = c.replaceFirst("(?is)^```(?:mermaid)?\\s*", "")
                .replaceFirst("(?is)```\\s*$", "")
                .trim();
        c = c.replace('（', '(')
                .replace('）', ')')
                .replace('：', ':')
                .replace('；', ';')
                .replace('，', ',');

        List<String> lines = new ArrayList<>();
        for (String line : c.split("\n")) {
            String t = line.stripTrailing();
            if (t.isBlank()) continue;
            if (t.trim().startsWith("```")) continue;
            lines.add(t);
        }
        return String.join("\n", lines).trim();
    }

    private byte[] createFallbackImage(String code) {
        try {
            int width = 1400;
            int height = 760;
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(230, 234, 242));
            g.fillRoundRect(36, 36, width - 72, height - 72, 24, 24);
            g.setColor(new Color(255, 255, 255));
            g.fillRoundRect(54, 54, width - 108, height - 108, 18, 18);
            g.setColor(new Color(84, 112, 198));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 28));
            g.drawString("图表渲染失败，已保留 Mermaid 源码", 86, 106);
            g.setColor(new Color(90, 90, 90));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 18));
            g.drawString("通常是 Mermaid 语法中包含服务端不兼容的字符、过长标签或未闭合节点。", 86, 140);

            g.setColor(new Color(245, 247, 250));
            g.fillRoundRect(82, 176, width - 164, height - 244, 12, 12);
            g.setColor(new Color(90, 90, 90));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
            int y = 216;
            for (String line : wrapCodeLines(code, 112, 20)) {
                g.drawString(line, 110, y);
                y += 26;
            }
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("Mermaid fallback image creation failed: {}", e.getMessage());
            return null;
        }
    }

    private List<String> wrapCodeLines(String code, int maxChars, int maxLines) {
        List<String> out = new ArrayList<>();
        if (code == null || code.isBlank()) return out;
        for (String sourceLine : code.split("\n")) {
            String line = sourceLine.replace('\t', ' ');
            while (line.length() > maxChars) {
                out.add(line.substring(0, maxChars));
                line = "  " + line.substring(maxChars);
                if (out.size() >= maxLines) {
                    out.add("...");
                    return out;
                }
            }
            out.add(line);
            if (out.size() >= maxLines) {
                out.add("...");
                return out;
            }
        }
        return out;
    }

    private byte[] ensureMinWidth(byte[] png, int minWidth) {
        try {
            int[] wh = imageSize(png);
            if (wh == null || wh[0] >= minWidth) return png;
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return png;
            double scale = (double) minWidth / src.getWidth();
            // 最多放大 3 倍，避免糊成一片
            scale = Math.min(scale, 3.0);
            int nw = (int) Math.round(src.getWidth() * scale);
            int nh = (int) Math.round(src.getHeight() * scale);
            BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(dst, "png", bos);
            byte[] out = bos.toByteArray();
            log.info("图表放大到高清: {}x{} → {}x{}", src.getWidth(), src.getHeight(), nw, nh);
            return out;
        } catch (Exception e) {
            log.warn("图片放大失败，使用原图: {}", e.getMessage());
            return png;
        }
    }

    private void putCache(String key, byte[] png) {
        if (cache.size() >= CACHE_MAX) {
            // 简单清空一半，避免无界增长
            int i = 0;
            for (String k : cache.keySet()) {
                cache.remove(k);
                if (++i >= CACHE_MAX / 2) break;
            }
        }
        cache.put(key, png);
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private byte[] renderViaKroki(String code) {
        try {
            String url = krokiBaseUrl + "/mermaid/png";
            byte[] png = webClient.post()
                    .uri(url)
                    .contentType(MediaType.TEXT_PLAIN)
                    .accept(MediaType.IMAGE_PNG)
                    .bodyValue(code)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(45))
                    .block();
            if (isPng(png)) {
                log.info("Kroki Mermaid 渲染成功: bytes={}, sourceLen={}", png.length, code.length());
                return png;
            }
            log.warn("Kroki 返回非 PNG: bytes={}", png == null ? 0 : png.length);
        } catch (Exception e) {
            log.warn("Kroki Mermaid 渲染失败: {}", e.getMessage());
        }
        return null;
    }

    private byte[] renderViaMermaidInk(String code) {
        try {
            String encoded = deflateBase64Url(code.getBytes(StandardCharsets.UTF_8));
            String url = "https://mermaid.ink/img/pako:" + encoded + "?bgColor=!white&theme=default";
            byte[] png = webClient.get()
                    .uri(url)
                    .accept(MediaType.IMAGE_PNG, MediaType.ALL)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(45))
                    .block();
            if (isPng(png) || (png != null && png.length > 100)) {
                log.info("mermaid.ink 渲染成功: bytes={}", png.length);
                return png;
            }
            String plain = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(code.getBytes(StandardCharsets.UTF_8));
            png = webClient.get()
                    .uri("https://mermaid.ink/img/" + plain + "?bgColor=!white")
                    .accept(MediaType.IMAGE_PNG, MediaType.ALL)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(45))
                    .block();
            if (isPng(png) || (png != null && png.length > 100)) {
                log.info("mermaid.ink(plain) 渲染成功: bytes={}", png.length);
                return png;
            }
        } catch (Exception e) {
            log.warn("mermaid.ink 渲染失败: {}", e.getMessage());
        }
        return null;
    }

    private static String deflateBase64Url(byte[] raw) {
        Deflater deflater = new Deflater(9, true);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            if (n > 0) bos.write(buf, 0, n);
            else break;
        }
        deflater.end();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
    }

    private static boolean isPng(byte[] bytes) {
        return bytes != null
                && bytes.length > 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47;
    }
}
