package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.model.enums.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PrototypeQualityGuard {

    static final String ASSET_PLACEHOLDER = "__PROTOTYPE_PRIMARY_ASSET__";
    private static final String GUARD_MARKER = "data-prototype-quality-guard";
    private static final Pattern NAMED_ASSET_PLACEHOLDER = Pattern.compile("__PROTOTYPE_ASSET_([a-zA-Z0-9_-]+)__");
    private static final Pattern BUTTON = Pattern.compile("(?is)<button\\b[^>]*>.*?</button>");
    private static final Pattern IMG_WITH_PLACEHOLDER = Pattern.compile(
            "(?is)<img\\b(?=[^>]*__PROTOTYPE_PRIMARY_ASSET__)[^>]*>");
    private static final Pattern IMG_WITH_EMPTY_SRC = Pattern.compile(
            "(?is)<img\\b(?=[^>]*\\bsrc\\s*=\\s*([\"'])\\s*\\1)[^>]*>");
    private static final Pattern NAV_TAG = Pattern.compile("(?is)<nav\\b[^>]*>.*?</nav>");
    private static final Pattern FORBIDDEN_NAV_CLASS = Pattern.compile(
            "(?is)<([a-z][a-z0-9]*)\\b[^>]*class\\s*=\\s*([\"'])[^\"']*(?:co-tabbar|bottom-nav|tab-bar|sidebar|side-nav)[^\"']*\\2[^>]*>.*?</\\1>");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Result inspectAndRepair(
            String content,
            Platform platform,
            String generationBrief,
            boolean hasResolvedAsset,
            String resolvedAssetDataUrl) {
        List<Asset> assets = hasResolvedAsset && resolvedAssetDataUrl != null
                ? List.of(new Asset("primary", "", resolvedAssetDataUrl))
                : List.of();
        return inspectAndRepairAssets(content, platform, generationBrief, assets);
    }

    public Result inspectAndRepairAssets(
            String content,
            Platform platform,
            String generationBrief,
            List<Asset> assets) {
        if (content == null || content.isBlank()) return new Result(content, List.of());
        List<Asset> availableAssets = assets == null ? List.of() : assets.stream()
                .filter(java.util.Objects::nonNull)
                .filter(asset -> asset.dataUrl() != null && !asset.dataUrl().isBlank())
                .toList();
        String trimmed = content.trim();
        if (trimmed.startsWith("[")) {
            return repairMultiPageAssets(trimmed, platform, generationBrief, availableAssets);
        }
        List<String> findings = new ArrayList<>();
        Asset primary = availableAssets.isEmpty() ? null : availableAssets.get(0);
        String prepared = replaceNamedAssets(content, availableAssets, findings);
        String html = repairHtml(prepared, platform, generationBrief,
                primary != null, primary == null ? null : primary.dataUrl(), findings);
        html = clearUnresolvedNamedAssets(html, findings);
        return new Result(html, List.copyOf(findings));
    }

    private Result repairMultiPageAssets(
            String content,
            Platform platform,
            String brief,
            List<Asset> assets) {
        List<String> findings = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!(root instanceof ArrayNode pages)) return new Result(content, List.of());
            boolean reuseAcrossPages = shouldReuseAssetAcrossPages(brief);
            boolean legacyAssetAvailable = !assets.isEmpty();
            for (JsonNode pageNode : pages) {
                if (!(pageNode instanceof ObjectNode page)) continue;
                String html = page.path("html").asText("");
                String title = page.path("title").asText("");
                int before = findings.size();
                String prepared = replaceNamedAssets(html, assets, findings);
                Asset pageAsset = selectPageAsset(assets, title, prepared);
                if (pageAsset == null && assets.size() == 1 && (reuseAcrossPages || legacyAssetAvailable)) {
                    pageAsset = assets.get(0);
                }
                String repaired = repairHtml(
                        prepared,
                        platform,
                        brief,
                        pageAsset != null,
                        pageAsset == null ? null : pageAsset.dataUrl(),
                        findings);
                repaired = clearUnresolvedNamedAssets(repaired, findings);
                page.put("html", repaired);
                if (!reuseAcrossPages && assets.size() == 1 && legacyAssetAvailable
                        && repaired.contains(assets.get(0).dataUrl())) {
                    legacyAssetAvailable = false;
                }
                if (findings.size() > before) findings.add("已检查页面：" + page.path("title").asText("未命名页面"));
            }
            return new Result(objectMapper.writeValueAsString(pages), List.copyOf(findings));
        } catch (Exception e) {
            log.warn("多页面原型质量检查解析失败，保留原内容: {}", e.getMessage());
            return new Result(content, List.of("多页面质量检查跳过：JSON 无法解析"));
        }
    }

    private String replaceNamedAssets(String html, List<Asset> assets, List<String> findings) {
        String result = html;
        for (Asset asset : assets) {
            String placeholder = namedPlaceholder(asset.key());
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, asset.dataUrl());
                findings.add("已替换页面素材：" + asset.key());
            }
        }
        return result;
    }

    private String clearUnresolvedNamedAssets(String html, List<String> findings) {
        Matcher matcher = NAMED_ASSET_PLACEHOLDER.matcher(html);
        if (!matcher.find()) return html;
        matcher.reset();
        String cleaned = matcher.replaceAll("");
        cleaned = IMG_WITH_EMPTY_SRC.matcher(cleaned).replaceAll("");
        findings.add("已清理未生成成功的页面素材占位符");
        return cleaned;
    }

    private Asset selectPageAsset(List<Asset> assets, String title, String html) {
        for (Asset asset : assets) {
            if (html.contains(asset.dataUrl())) return asset;
        }
        String normalizedTitle = normalizePage(title);
        for (Asset asset : assets) {
            String target = normalizePage(asset.targetPage());
            if (!target.isBlank() && (normalizedTitle.contains(target) || target.contains(normalizedTitle))) {
                return asset;
            }
        }
        return null;
    }

    private String normalizePage(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s页面界面_-]+", "");
    }

    private String namedPlaceholder(String key) {
        String safe = key == null ? "primary" : key.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return "__PROTOTYPE_ASSET_" + (safe.isBlank() ? "primary" : safe) + "__";
    }

    private String repairHtml(
            String html,
            Platform platform,
            String brief,
            boolean hasAsset,
            String assetDataUrl,
            List<String> findings) {
        String result = html;

        if (hasAsset && assetDataUrl != null && !assetDataUrl.isBlank()) {
            if (result.contains(ASSET_PLACEHOLDER)) {
                result = replaceFirstAssetAndRemoveDuplicates(result, assetDataUrl);
                findings.add("已替换核心素材占位符");
            } else if (!result.contains(assetDataUrl) && !result.contains("data-generated-primary-asset")) {
                result = insertPrimaryAsset(result, assetDataUrl, brief);
                findings.add("HTML 漏用了核心素材，已自动补入主体图");
            }
        } else if (result.contains(ASSET_PLACEHOLDER)) {
            result = IMG_WITH_PLACEHOLDER.matcher(result).replaceAll("");
            result = result.replace(ASSET_PLACEHOLDER, "");
            findings.add("已清理未解析的图片占位符");
        }

        String withoutBrokenImages = IMG_WITH_EMPTY_SRC.matcher(result).replaceAll("");
        if (!withoutBrokenImages.equals(result)) {
            result = withoutBrokenImages;
            findings.add("已移除空地址图片，避免预览显示破图");
        }

        if (explicitlyForbidsNavigation(brief)) {
            String withoutNav = transformMarkupBeforeScript(result, markup -> {
                String cleaned = NAV_TAG.matcher(markup).replaceAll("");
                return FORBIDDEN_NAV_CLASS.matcher(cleaned).replaceAll("");
            });
            if (!withoutNav.equals(result)) {
                result = withoutNav;
                findings.add("已移除需求明确禁止的导航结构");
            }
        }

        if (requiresSingleButton(brief)) {
            String repaired = transformMarkupBeforeScript(result, this::keepFirstButton);
            if (!repaired.equals(result)) {
                result = repaired;
                findings.add("已按明确要求保留一个按钮");
            }
        }

        if (!result.contains(GUARD_MARKER)) {
            result = injectGuardCss(result, platform);
            findings.add("已注入按钮居中、图片边界和横向溢出兜底");
        }
        return result;
    }

    private String insertPrimaryAsset(String html, String dataUrl, String brief) {
        boolean immersiveCenter = containsAny(brief, "主体居中", "正中间", "页面中间", "画面中央", "居中展示");
        String className = immersiveCenter
                ? "proto-generated-primary-asset proto-generated-primary-asset--centered"
                : "proto-generated-primary-asset";
        String image = "<img data-generated-primary-asset=\"true\" class=\"" + className
                + "\" src=\"" + dataUrl + "\" alt=\"核心视觉素材\">";
        int bodyClose = indexOfIgnoreCase(html, "</body>");
        if (bodyClose >= 0) return html.substring(0, bodyClose) + image + html.substring(bodyClose);
        return html + image;
    }

    private String replaceFirstAssetAndRemoveDuplicates(String html, String dataUrl) {
        Matcher matcher = IMG_WITH_PLACEHOLDER.matcher(html);
        StringBuffer output = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            count++;
            String replacement = count == 1
                    ? matcher.group().replace(ASSET_PLACEHOLDER, dataUrl)
                    : "";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString().replace(ASSET_PLACEHOLDER, dataUrl);
    }

    private String keepFirstButton(String html) {
        Matcher matcher = BUTTON.matcher(html);
        StringBuffer output = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(output, count == 1 ? Matcher.quoteReplacement(matcher.group()) : "");
        }
        matcher.appendTail(output);
        return count > 1 ? output.toString() : html;
    }

    private String transformMarkupBeforeScript(String html, java.util.function.UnaryOperator<String> transform) {
        int scriptStart = indexOfIgnoreCase(html, "<script");
        if (scriptStart < 0) return transform.apply(html);
        return transform.apply(html.substring(0, scriptStart)) + html.substring(scriptStart);
    }

    private String injectGuardCss(String html, Platform platform) {
        String mobileRule = platform == Platform.WEB ? "" : "body{overflow-x:hidden;}";
        String css = """
                <style data-prototype-quality-guard>
                *,*::before,*::after{box-sizing:border-box;}
                %s
                img{max-width:100%%;}
                button,[role="button"]{display:inline-flex;align-items:center;justify-content:center;text-align:center;}
                .proto-generated-primary-asset{display:block;width:min(72%%,560px);height:auto;object-fit:contain;margin:24px auto;}
                .proto-generated-primary-asset--centered{position:absolute;left:50%%;top:48%%;transform:translate(-50%%,-50%%);max-height:58%%;margin:0;z-index:2;pointer-events:none;}
                </style>
                """.formatted(mobileRule);
        int headClose = indexOfIgnoreCase(html, "</head>");
        if (headClose >= 0) return html.substring(0, headClose) + css + html.substring(headClose);
        return css + html;
    }

    private boolean explicitlyForbidsNavigation(String brief) {
        return containsAny(brief, "禁止导航", "不要导航", "不需要导航", "无导航", "禁止底部栏", "不要底部导航");
    }

    private boolean requiresSingleButton(String brief) {
        return containsAny(brief, "仅一个按钮", "只有一个按钮", "只保留一个按钮", "下方一个按钮", "下方仅一个");
    }

    private boolean shouldReuseAssetAcrossPages(String brief) {
        return containsAny(brief, "每页", "每个页面", "所有页面", "全部页面", "贯穿", "无论哪个页面", "无论是哪页");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase().indexOf(needle.toLowerCase());
    }

    public record Result(String html, List<String> findings) {
    }

    public record Asset(String key, String targetPage, String dataUrl) {
    }
}
