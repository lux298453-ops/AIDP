package com.example.aidocumentplatform.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;

/**
 * 参考图压缩 / MIME 推断工具。
 * 限制最大边长与文件体积，避免 vision 请求过大。
 */
@Slf4j
public final class ImageUtils {

    public static final int MAX_EDGE = 1280;
    public static final int MAX_BYTES = 2 * 1024 * 1024; // 2MB after compress

    private ImageUtils() {}

    public record PreparedImage(byte[] bytes, String mimeType, String base64, int width, int height) {}

    /**
     * 读取并压缩图片，返回 PNG/JPEG 字节 + base64。
     */
    public static PreparedImage prepare(byte[] raw, String originalName, String contentType) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("参考图为空");
        }
        if (raw.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("参考图不能超过 8MB");
        }
        String mime = normalizeMime(contentType, originalName);
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) {
                // 读不了就原样 base64（仍校验扩展名）
                return new PreparedImage(raw, mime, Base64.getEncoder().encodeToString(raw), 0, 0);
            }
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = 1.0;
            int maxEdge = Math.max(w, h);
            if (maxEdge > MAX_EDGE) {
                scale = (double) MAX_EDGE / maxEdge;
            }
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));

            BufferedImage dst = new BufferedImage(nw, nh,
                    mime.contains("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!mime.contains("png")) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, nw, nh);
            }
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();

            String format = mime.contains("png") ? "png" : "jpg";
            if (mime.contains("webp") || mime.contains("gif")) {
                format = "png";
                mime = "image/png";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(dst, format, bos);
            byte[] out = bos.toByteArray();

            // 仍过大则再缩一档
            if (out.length > MAX_BYTES && format.equals("jpg")) {
                // re-encode as lower quality isn't available without ImageWriteParam; shrink more
                double s2 = Math.sqrt((double) MAX_BYTES / out.length);
                int nw2 = Math.max(1, (int) (nw * s2));
                int nh2 = Math.max(1, (int) (nh * s2));
                BufferedImage dst2 = new BufferedImage(nw2, nh2, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = dst2.createGraphics();
                g2.setColor(Color.WHITE);
                g2.fillRect(0, 0, nw2, nh2);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(dst, 0, 0, nw2, nh2, null);
                g2.dispose();
                bos.reset();
                ImageIO.write(dst2, "jpg", bos);
                out = bos.toByteArray();
                nw = nw2;
                nh = nh2;
                mime = "image/jpeg";
            }

            log.info("参考图压缩: {}x{} → {}x{}, {}bytes → {}bytes, mime={}",
                    w, h, nw, nh, raw.length, out.length, mime);
            return new PreparedImage(out, mime, Base64.getEncoder().encodeToString(out), nw, nh);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("参考图处理失败，使用原图 base64: {}", e.getMessage());
            return new PreparedImage(raw, mime, Base64.getEncoder().encodeToString(raw), 0, 0);
        }
    }

    public static String normalizeMime(String contentType, String fileName) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            String c = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
            if (c.equals("image/jpg")) return "image/jpeg";
            return c;
        }
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    public static boolean isSupportedImage(String fileName, String contentType) {
        String mime = normalizeMime(contentType, fileName);
        return mime.equals("image/jpeg") || mime.equals("image/png")
                || mime.equals("image/webp") || mime.equals("image/gif");
    }
}
