package com.example.aidocumentplatform.util;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.error.PSystemError;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlantUML → 高清 PNG（纯 Java，无外部进程依赖）。
 *
 * <ul>
 *   <li>使用 PlantUML 官方 Java 库直接渲染，不再依赖 mmdc / 外部服务</li>
 *   <li>注入高质量 skinparam：白底、深色连接线、16px 字号、150 DPI</li>
 *   <li>无 Graphviz 时自动启用 smetana 纯 Java 布局引擎</li>
 *   <li>内存缓存（按源码 hash），避免同文档多章节重复渲染</li>
 * </ul>
 */
@Slf4j
@Component
public class PlantUmlImageRenderer {

    public static final int MIN_EXPORT_WIDTH = 3600;

    /**
     * 目标最小文字像素高度。
     * 宽度归一化会把矮宽图（如横向页面结构图）放大较少、导致文字偏小；
     * 按文字像素归一化可保证所有图表放大查看/导出时文字大小接近一致。
     * 标准注入管线（defaultFontSize 16 × scale 4 × dpi 150）原生文字约 100px，
     * 目标 160px 与窄高流程图被宽度归一化后的文字（约 165px）基本持平。
     */
    private static final int MIN_TEXT_HEIGHT_PX = 160;

    private static final Pattern FONT_SIZE_PATTERN = Pattern.compile(
            "(?is)skinparam\\s+defaultFontSize\\s+(\\d+)");

    /**
     * PlantUML 默认将输出图片限制在 4096x4096，超出会被裁剪成只有一部分的图。
     * 通过系统属性 PLANTUML_LIMIT_SIZE 放宽限制，保证大图完整渲染。
     */
    private static final int MAX_IMAGE_LIMIT = 20000;

    static {
        if (System.getProperty("PLANTUML_LIMIT_SIZE") == null) {
            System.setProperty("PLANTUML_LIMIT_SIZE", String.valueOf(MAX_IMAGE_LIMIT));
        }
    }

    private static final Pattern PLANTUML_FENCE = Pattern.compile(
            "```plantuml[ \\t]*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAW_BLOCK = Pattern.compile(
            "(?s)@startuml[\\s\\S]*?@enduml");

    /** 注入到 @startuml 之后的通用高质量样式（仅当源码未自定义 skinparam 时） */
    private static final String SKIN_PARAMS = """
            scale 8
            skinparam shadowing false
            skinparam backgroundColor white
            skinparam defaultFontName "Microsoft YaHei"
            skinparam defaultFontSize 16
            skinparam dpi 300
            skinparam ArrowColor #333333
            """;

    /** 导出专用高 DPI 设置，追加到 @enduml 之前覆盖低 DPI */
    private static final String EXPORT_SKIN_PARAMS = """
            skinparam dpi 600
            """;

    private final boolean graphvizAvailable;
    /** plantuml 源码 hash → PNG */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> exportCache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> svgCache = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 64;

    public PlantUmlImageRenderer() {
        this.graphvizAvailable = detectGraphviz();
        log.info("PlantUML 渲染器就绪, graphviz={}", graphvizAvailable);
    }

    public boolean isGraphvizAvailable() {
        return graphvizAvailable;
    }

    /** 从章节 content 提取第一段 plantuml 源码（支持 ```plantuml 围栏或裸 @startuml 块） */
    public String extractPlantUml(String content) {
        if (content == null || content.isBlank()) return null;
        String text = content.replace("\\n", "\n").replace("\r\n", "\n");
        Matcher m = PLANTUML_FENCE.matcher(text);
        if (m.find()) {
            String code = m.group(1).trim();
            return code.isEmpty() ? null : code;
        }
        Matcher raw = RAW_BLOCK.matcher(text);
        if (raw.find()) {
            String code = raw.group(0).trim();
            return code.isEmpty() ? null : code;
        }
        return null;
    }

    public boolean hasPlantUml(String content) {
        return extractPlantUml(content) != null;
    }

    /**
     * 渲染 plantuml → 高清 PNG（宽 ≥ {@link #MIN_EXPORT_WIDTH}，带缓存）。
     */
    public byte[] renderPlantUmlToPng(String source) {
        return renderPlantUmlToPng(source, false);
    }

    /**
     * 渲染 plantuml → 高清 PNG，export=true 时使用 600 DPI 并放大到最小宽度。
     */
    public byte[] renderPlantUmlToPng(String source, boolean forExport) {
        if (source == null || source.isBlank()) return null;
        String code = sanitize(source);
        if (code.isBlank()) return null;
        String key = sha1(code) + (forExport ? "_export" : "");
        Map<String, byte[]> targetCache = forExport ? exportCache : cache;
        byte[] cached = targetCache.get(key);
        if (cached != null) return cached;

        String full = forExport ? ensureExportSource(code) : ensureFullSource(code);
        byte[] png = render(full, FileFormat.PNG);
        if (png == null) {
            // 重试一次：强制 smetana 纯 Java 布局（兜底无 Graphviz 环境）
            String smetana = forceSmetana(full);
            if (!smetana.equals(full)) png = render(smetana, FileFormat.PNG);
        }
        if (png == null) {
            log.warn("PlantUML 渲染失败，使用占位图。sourceLen={}, source={}",
                    code.length(), code.substring(0, Math.min(300, code.length())).replace('\n', ' '));
            png = createFallbackImage(code);
        }
        if (png == null) return null;

        if (forExport) {
            png = ensureMinWidth(png, MIN_EXPORT_WIDTH, code);
        }
        putCache(targetCache, key, png);
        return png;
    }

    public byte[] renderPlantUmlToSvg(String source) {
        if (source == null || source.isBlank()) return null;
        String code = sanitize(source);
        if (code.isBlank()) return null;
        String key = sha1(code);
        byte[] cached = svgCache.get(key);
        if (cached != null) return cached;

        String full = ensureFullSource(code);
        byte[] svg = render(full, FileFormat.SVG);
        if (svg == null) {
            String smetana = forceSmetana(full);
            if (!smetana.equals(full)) svg = render(smetana, FileFormat.SVG);
        }
        if (svg == null) {
            log.warn("PlantUML SVG 渲染失败: sourceLen={}, source={}",
                    code.length(), code.substring(0, Math.min(300, code.length())).replace('\n', ' '));
            return null;
        }
        putSvgCache(key, svg);
        return svg;
    }

    // ── private ──────────────────────────────────────────────

    private String sanitize(String raw) {
        String c = raw == null ? "" : raw.trim();
        c = c.replace("\r\n", "\n").replace("\r", "\n").replace("\\n", "\n").replace("\uFEFF", "");
        c = c.replaceFirst("(?is)^```(?:plantuml)?\\s*", "").replaceFirst("(?is)```\\s*$", "").trim();
        // 修复 LLM/编辑器可能产生的 PlantUML 换行丢失（语句被拼接/粘连成一行）：
        // 仅在语句边界前不是换行时补 \n，对正常多行源码是 no-op。
        c = repairLostNewlines(c);
        // 修复 skinparam 参数名与值粘连（RoundCorner12 / BackgroundColor#F8FBFF）：
        // 在 PlantUML 1.2026.x 中粘连写法是硬错误（整图 PSystemError），需补空格。
        c = repairSkinparamValues(c);
        c = c.trim();
        if (c.isEmpty()) return "";
        // 至少保证 @startuml / @enduml 成对
        if (!c.startsWith("@startuml")) c = "@startuml\n" + c;
        if (!c.endsWith("@enduml")) c = c + "\n@enduml";
        return c;
    }

    /**
     * 修复 PlantUML 源码换行丢失：LLM 输出或编辑器往返有时会把多条语句拼/粘成一行
     * （如 {@code @startuml!pragma layout smetana left to right direction skinparam rectangle {...}}），
     * PlantUML 无法在单行内切分语句，会误判成 class 图直接报 Syntax Error。
     *
     * <p>策略：只在语句边界前不是换行时才补 {@code \n}，因此对正常多行源码完全无副作用；
     * 元素声明（rectangle/component 等）仅在「真正粘连」时拆，避免拆坏 {@code skinparam rectangle {}}。
     */
    static String repairLostNewlines(String c) {
        String r = c == null ? "" : c;
        // @startuml 后必须换行
        r = r.replaceAll("@startuml(?![\\r\\n])", "@startuml\n");
        // 指令类语句边界：前不是换行即补（这些指令不会出现在节点标签里）
        r = r.replaceAll("(?<![\\r\\n])(?=@enduml\\b)", "\n");
        r = r.replaceAll("(?<![\\r\\n])(?=\\b(?:skinparam|!pragma|scale|hide|show|note|together|namespace|split|newpage)\\b)", "\n");
        r = r.replaceAll("(?<![\\r\\n])(?=(?:left to right|top to bottom|right to left|bottom to top) direction)", "\n");
        // 元素声明边界：先把引号内文本遮蔽起来，避免拆进标签文字；
        // 再允许在「粘连或空格分隔」的元素声明前补换行，同时不拆 skinparam 的直属参数 rectangle。
        Map<String, String> maskedQuotes = new LinkedHashMap<>();
        StringBuilder holder = new StringBuilder();
        Matcher qm = Pattern.compile("\"[^\"\\n]*\"").matcher(r);
        int qi = 0;
        while (qm.find()) {
            String ph = "\u0001Q" + (qi++) + "\u0001";
            maskedQuotes.put(ph, qm.group());
            qm.appendReplacement(holder, Matcher.quoteReplacement(ph));
        }
        qm.appendTail(holder);
        String masked = holder.toString();
        // 先遮蔽 skinparam 语句的「类型 + 关键字」三连词（如 skinparam packageStyle rectangle），
        // 避免下面的元素拆行把其中的 rectangle/package 等关键字误拆成独立节点声明。
        Map<String, String> maskedSkinparams = new LinkedHashMap<>();
        StringBuilder sk = new StringBuilder();
        Matcher sm = Pattern.compile("(?is)\\bskinparam\\s+[a-z0-9_]+\\s+[a-z0-9_]+").matcher(masked);
        int si = 0;
        while (sm.find()) {
            String ph = "\u0001SK" + (si++) + "\u0001";
            maskedSkinparams.put(ph, sm.group());
            sm.appendReplacement(sk, Matcher.quoteReplacement(ph));
        }
        sm.appendTail(sk);
        masked = sk.toString();
        masked = masked.replaceAll("(?<!skinparam )(?<![\\r\\n])(?=\\b(?:rectangle|component|package|actor|usecase|class|interface|entity|node)\\b)", "\n");
        // 关系粘连：把拼在声明行尾的关系拆到独立行（as <别名> <来源别名> --> ...），
        // 例如 rectangle "x" as workbench home --> workbench → 拆出独立行 home --> workbench。
        // 对正常多行源码（as 后即换行）匹配后重排结果与原文一致，是 no-op。
        masked = masked.replaceAll("(?is)(as\\s+[a-z0-9_]+)\\s+([a-z0-9_]+\\s*(?:-->|->|\\.\\.>|\\.>))", "$1\n$2");
        for (Map.Entry<String, String> e : maskedSkinparams.entrySet()) {
            masked = masked.replace(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : maskedQuotes.entrySet()) {
            masked = masked.replace(e.getKey(), e.getValue());
        }
        return masked;
    }

    /**
     * 匹配单行 skinparam 块：{@code skinparam rectangle { BackgroundColor #F8FBFF ... }}。
     * PlantUML 1.2026.x 中整块挤在一行是硬错误（PSystemError），必须把 { 与 } 各放一行。
     */
    private static final Pattern SINGLE_LINE_SKINPARAM_BLOCK = Pattern.compile(
            "(?is)^(skinparam\\s+[a-z0-9_]+\\s*\\{)([^{}]*)(\\})$");

    /**
     * 修复 skinparam 参数名与值粘连的写法：{@code RoundCorner12} → {@code RoundCorner 12}、
     * {@code BackgroundColor#F8FBFF} → {@code BackgroundColor #F8FBFF}，
     * 并把单行 {@code skinparam X { ... }} 块展开为多行（{ 与 } 各占一行，块参数可保持一行）。
     * 这些写法在 PlantUML 1.2026.x 中都是硬错误（整图 PSystemError 渲染失败）。
     *
     * <p>只在 skinparam 语句及 {@code skinparam ... { ... }} 块内的行生效，避免误伤节点别名
     * （如 {@code A1}、{@code B2}）；对已带空格或数字结尾的节点名均无副作用。
     */
    static String repairSkinparamValues(String c) {
        String r = c == null ? "" : c;
        String[] lines = r.split("\n", -1);
        boolean inSkinparamBlock = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            boolean isSkinparam = t.startsWith("skinparam");
            if (inSkinparamBlock && t.equals("}")) {
                inSkinparamBlock = false;
            }
            String fixed = null;
            if (isSkinparam || inSkinparamBlock) {
                fixed = t.replaceAll(
                        // 颜色值粘连：BackgroundColor#F8FBFF -> BackgroundColor #F8FBFF（仅当 # 直接跟在字母后）
                        "(?i)(?<=[a-z])(?=#[0-9a-fA-F])"
                                // 数值参数粘连：RoundCorner12 -> RoundCorner 12（白名单参数名后紧跟数字）
                                + "|(?<=\\b(?:roundcorner|roundcornerarcsize|defaultfontsize|fontsize|dpi|"
                                + "arrowthickness|linethickness|maxmessagesize|maximagesize|padding|margin|"
                                + "minwidth|maxwidth))(?=[0-9])",
                        " ");
                Matcher m = SINGLE_LINE_SKINPARAM_BLOCK.matcher(fixed);
                if (m.matches()) {
                    fixed = m.group(1) + "\n" + m.group(2).trim() + "\n" + m.group(3);
                }
                int leading = 0;
                while (leading < line.length() && Character.isWhitespace(line.charAt(leading))) {
                    leading++;
                }
                String indent = line.substring(0, leading);
                out.append(indent).append(fixed).append(line.substring(leading + t.length()));
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
            if (isSkinparam && t.contains("{")) {
                // 单行块已展开闭合（含 }）则不进块模式；否则标记进入多行块
                inSkinparamBlock = !t.contains("}");
            }
        }
        return out.toString();
    }

    private String ensureFullSource(String code) {
        String c = code.trim();
        StringBuilder sb = new StringBuilder();
        String rest = c;
        if (c.startsWith("@startuml")) {
            int nl = c.indexOf('\n');
            if (nl >= 0) {
                sb.append(c, 0, nl).append('\n');
                rest = c.substring(nl + 1);
            } else {
                sb.append(c).append('\n');
                rest = "";
            }
        } else {
            sb.append("@startuml\n");
        }
        if (!graphvizAvailable) {
            sb.append("!pragma layout smetana\n");
        }
        // 仅在源码未自定义样式时注入通用 skinparam，避免覆盖用户自定义
        if (!c.contains("skinparam")) {
            sb.append(SKIN_PARAMS);
        } else if (!graphvizAvailable && !c.contains("smetana") && !c.contains("elturco")) {
            sb.append("!pragma layout smetana\n");
        }
        sb.append(rest);
        if (!c.endsWith("@enduml")) {
            sb.append('\n').append("@enduml\n");
        }
        return sb.toString();
    }

    /** 导出专用：始终注入高 DPI，覆盖源码中可能已有的低 DPI 设置 */
    private String ensureExportSource(String code) {
        String c = code.trim();
        StringBuilder sb = new StringBuilder();
        String rest = c;
        if (c.startsWith("@startuml")) {
            int nl = c.indexOf('\n');
            if (nl >= 0) {
                sb.append(c, 0, nl).append('\n');
                rest = c.substring(nl + 1);
            } else {
                sb.append(c).append('\n');
                rest = "";
            }
        } else {
            sb.append("@startuml\n");
        }
        if (!graphvizAvailable) {
            sb.append("!pragma layout smetana\n");
        }
        // 去掉 rest 末尾的 @enduml
        String body = rest.replaceAll("(?s)\\s*@enduml\\s*$", "");
        sb.append(body);
        if (!body.isEmpty() && !body.endsWith("\n")) {
            sb.append('\n');
        }
        // 始终注入高 DPI（在 @enduml 之前，后定义的优先）
        sb.append(EXPORT_SKIN_PARAMS);
        sb.append("@enduml\n");
        return sb.toString();
    }

    private String forceSmetana(String full) {
        if (full.contains("!pragma layout smetana") || full.contains("!pragma layout elturco")) {
            return full;
        }
        return full.replaceFirst("(?s)(@startuml\\b.*?)(\\n|$)", "$1\n!pragma layout smetana\n");
    }

    private byte[] render(String fullSource, FileFormat format) {
        try {
            SourceStringReader reader = new SourceStringReader(fullSource);
            for (BlockUml block : reader.getBlocks()) {
                if (block.getDiagram() instanceof PSystemError) {
                    log.warn("PlantUML 语法错误，跳过渲染: source={}",
                            fullSource.substring(0, Math.min(200, fullSource.length())).replace('\n', ' '));
                    return null;
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            reader.outputImage(bos, new FileFormatOption(format));
            byte[] output = bos.toByteArray();
            if (format == FileFormat.PNG && isPng(output) && output.length > 500) {
                log.info("PlantUML PNG 渲染成功: bytes={}", output.length);
                return output;
            }
            if (format == FileFormat.SVG && isSvg(output)) {
                log.info("PlantUML SVG 渲染成功: bytes={}", output.length);
                return output;
            }
            log.warn("PlantUML 输出异常: format={}, bytes={}", format, output == null ? 0 : output.length);
        } catch (Exception e) {
            log.warn("PlantUML 渲染异常: {}", e.getMessage());
        }
        return null;
    }

    private boolean detectGraphviz() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] candidates = isWindows
                ? new String[]{
                    "C:\\Program Files\\Graphviz\\bin\\dot.exe",
                    "C:\\Program Files (x86)\\Graphviz\\bin\\dot.exe",
                    "C:\\ProgramData\\chocolatey\\bin\\dot.exe",
                }
                : new String[]{"/usr/bin/dot", "/usr/local/bin/dot"};
        for (String path : candidates) {
            if (Files.exists(Path.of(path))) return true;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("dot", "-V");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] createFallbackImage(String code) {
        try {
            int width = 2800;
            int height = 1520;
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(230, 234, 242));
            g.fillRoundRect(72, 72, width - 144, height - 144, 48, 48);
            g.setColor(new Color(255, 255, 255));
            g.fillRoundRect(108, 108, width - 216, height - 216, 36, 36);
            g.setColor(new Color(84, 112, 198));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 56));
            g.drawString("图表渲染失败，已保留 PlantUML 源码", 172, 212);
            g.setColor(new Color(90, 90, 90));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 36));
            g.drawString("通常是 PlantUML 语法中包含不兼容字符、过长标签或未闭合节点。", 172, 280);

            g.setColor(new Color(245, 247, 250));
            g.fillRoundRect(164, 352, width - 328, height - 488, 24, 24);
            g.setColor(new Color(90, 90, 90));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 36));
            int y = 432;
            for (String line : wrapCodeLines(code, 112, 20)) {
                g.drawString(line, 110, y);
                y += 52;
            }
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("PlantUML fallback image creation failed: {}", e.getMessage());
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

    /**
     * 按「最小宽度」与「最小文字像素」取较大者归一化放大，保证：
     * 1. 输出宽度不低于 minWidth（Word 导出清晰度基线）
     * 2. 矮宽图（如 left to right 的页面结构图）也能把文字放大到可清晰阅读
     */
    private byte[] ensureMinWidth(byte[] png, int minWidth, String source) {
        try {
            int[] wh = imageSize(png);
            if (wh == null || wh[0] <= 0 || wh[1] <= 0) return png;
            if (wh[0] >= minWidth) return png;
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return png;
            double scale = Math.min((double) minWidth / src.getWidth(), 4.0);
            if (scale <= 1.05) return png;
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
            log.info("PlantUML 小图轻度放大: {}x{} -> {}x{} (scale={})",
                    src.getWidth(), src.getHeight(), nw, nh, String.format("%.2f", scale));
            return out;
        } catch (Exception e) {
            log.warn("图片放大失败，使用原图: {}", e.getMessage());
            return png;
        }
    }

    private double estimateNativeTextPx(String source) {
        int fontSize = 16;
        double dpi = 150;
        if (source != null && source.contains("skinparam")) {
            Matcher m = FONT_SIZE_PATTERN.matcher(source);
            if (m.find()) {
                try {
                    fontSize = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // 保持默认
                }
            }
            dpi = 96;
            return fontSize * (dpi / 96.0);
        }
        return fontSize * 4.0 * (dpi / 96.0);
    }

    private void putCache(Map<String, byte[]> targetCache, String key, byte[] png) {
        if (targetCache.size() >= CACHE_MAX) {
            int i = 0;
            for (String k : targetCache.keySet()) {
                targetCache.remove(k);
                if (++i >= CACHE_MAX / 2) break;
            }
        }
        targetCache.put(key, png);
    }

    private void putCache(String key, byte[] png) {
        putCache(cache, key, png);
    }

    private void putSvgCache(String key, byte[] svg) {
        if (svgCache.size() >= CACHE_MAX) {
            int i = 0;
            for (String k : svgCache.keySet()) {
                svgCache.remove(k);
                if (++i >= CACHE_MAX / 2) break;
            }
        }
        svgCache.put(key, svg);
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

    private static boolean isPng(byte[] bytes) {
        return bytes != null
                && bytes.length > 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47;
    }

    private static boolean isSvg(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        return text.contains("<svg");
    }

    private static int[] imageSize(byte[] bytes) {
        return MermaidImageRenderer.imageSize(bytes);
    }
}
