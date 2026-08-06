package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.model.enums.TaskType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.service.PrototypeGenerateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrototypeGenerateServiceImpl implements PrototypeGenerateService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final AiClient aiClient;
    private final PrototypePromptTemplate promptTemplate;
    private final TaskService taskService;
    private final ApplicationContext applicationContext;
    private final IdempotentTaskService idempotentTaskService;
    private final AsyncTaskLifecycleService asyncTaskLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Long submit(PrototypeGenerateRequest request, Long userId) {
        boolean hasRef = request.getReferenceImageBase64() != null
                && !request.getReferenceImageBase64().isBlank();
        IdempotentTaskService.TaskReservation reservation =
                idempotentTaskService.createOrReuseTask(userId, TaskType.PROTOTYPE, buildTaskInput(request, hasRef));
        AsyncTask task = reservation.task();
        log.info("原型生成任务: taskId={}, type={}, platform={}, hasRef={}",
                task.getId(), request.getPrototypeType(), request.getPlatform(), hasRef);
        if (reservation.created()) {
            applicationContext.getBean(PrototypeGenerateServiceImpl.class)
                    .execute(task.getId(), request, userId);
        }
        return task.getId();
    }

    private String buildTaskInput(PrototypeGenerateRequest request, boolean hasRef) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("platform", request.getPlatform().name());
            root.put("prototypeType", request.getPrototypeType().name());
            root.put("pageMorphology", request.getPageMorphology() == null ? "" : request.getPageMorphology().name());
            root.put("description", request.getDescription());
            root.put("hasReferenceImage", hasRef);
            if (hasRef && request.getReferenceImageFileName() != null) {
                root.put("referenceImageFileName", request.getReferenceImageFileName());
            }
            if (request.getReferenceImagePath() != null) {
                root.put("referenceImagePath", request.getReferenceImagePath());
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建原型任务参数失败", e);
        }
    }
    @Async("asyncTaskExecutor")
    public void execute(Long taskId, PrototypeGenerateRequest request, Long userId) {
        if (asyncTaskRepository.findById(taskId).isEmpty()) return;
        try {
            asyncTaskLifecycleService.markRunning(taskId);
            boolean hasRef = request.getReferenceImageBase64() != null
                    && !request.getReferenceImageBase64().isBlank();
            taskService.pushProgress(taskId, 15,
                    hasRef ? "AI 正在根据参考图生成原型..." : "AI 正在生成原型...");

            String systemPrompt = promptTemplate.getSystemPrompt();
            String userPrompt = promptTemplate.buildUserPrompt(
                    request.getDescription(),
                    request.getPlatform(),
                    request.getPrototypeType(),
                    hasRef,
                    request.getReferenceImageFileName(),
                    request.getPageMorphology());

            String aiOutput;
            AiRequestContext.setUserId(userId);
            try {
                if (hasRef) {
                    aiOutput = aiClient.generateWithImageStream(
                            systemPrompt,
                            userPrompt,
                            request.getReferenceImageMimeType(),
                            request.getReferenceImageBase64(),
                            delta -> taskService.pushContentDelta(taskId, delta));
                } else {
                    aiOutput = aiClient.generateStream(systemPrompt, userPrompt,
                            delta -> taskService.pushContentDelta(taskId, delta));
                }
            } finally {
                AiRequestContext.clear();
            }

            // 先清洗/落库再推 SSE，避免进度推送异常影响保存
            String content = request.getPrototypeType() == PrototypeType.MULTI_PAGE
                    ? cleanJson(aiOutput, request.getPlatform())
                    : cleanHtml(aiOutput, request.getPlatform());

            PrototypeResult proto = PrototypeResult.builder()
                    .userId(userId).taskId(taskId).prdDocumentId(request.getPrdDocumentId())
                    .prototypeType(request.getPrototypeType()).platform(request.getPlatform())
                    .content(content).build();
            proto = asyncTaskLifecycleService.savePrototypeResultAndMarkSuccess(taskId, proto);
            taskService.pushProgress(taskId, 100, "原型生成完成");
            log.info("原型生成成功: taskId={}, resultId={}, type={}, hasRef={}, len={}",
                    taskId, proto.getId(), request.getPrototypeType(), hasRef, content.length());

        } catch (Exception e) {
            log.error("原型生成失败: taskId={}", taskId, e);
            asyncTaskLifecycleService.markFailed(taskId, truncate(e.getMessage(), 500));
            taskService.pushProgress(taskId, 0, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 清洗模型输出为可预览 HTML。
     * Grok 等模型常夹带 markdown / 思考段落 / 不完整文档，这里尽量抽出完整 HTML，
     * 并对「只有 class、几乎没有 CSS」的结果注入基础样式，避免预览完全裸奔。
     */
    private String cleanHtml(String raw, Platform platform) {
        if (raw == null || raw.isBlank()) return "";
        String s = stripCodeFence(raw.trim());

        // 去掉常见“思考/说明”前缀，定位到真正的 HTML
        int doctype = indexOfIgnoreCase(s, "<!DOCTYPE");
        int htmlTag = indexOfIgnoreCase(s, "<html");
        int start = -1;
        if (doctype >= 0 && (htmlTag < 0 || doctype <= htmlTag)) start = doctype;
        else if (htmlTag >= 0) start = htmlTag;
        else start = s.indexOf('<');
        if (start > 0) s = s.substring(start);

        int end = s.lastIndexOf('>');
        if (end > 0 && end < s.length() - 1) s = s.substring(0, end + 1);
        s = s.trim();
        s = repairMalformedCssValues(s);

        // 模型只吐了片段时，包一层最小文档结构
        if (!looksLikeFullDocument(s)) {
            s = wrapAsDocument(s);
        }

        s = addPlatformMarker(s, platform);
        // 固定设计系统 CSS 优先注入，随后注入质量守卫兜底
        s = injectPrototypeQualityStyles(s, platform);
        return s;
    }

    /**
     * 多页 JSON：清洗数组，并对每个 page.html 再走 cleanHtml，
     * 避免多页场景下样式缺失 / 文档结构不完整。
     */
    private String cleanJson(String raw, Platform platform) {
        if (raw == null) return "[]";
        String s = stripCodeFence(raw.trim());
        int start = s.indexOf('['), end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return s;
        String json = s.substring(start, end + 1);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) return json;
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            int order = 1;
            for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                com.fasterxml.jackson.databind.node.ObjectNode page = mapper.createObjectNode();
                String title = item.path("title").asText("页面 " + order);
                int ord = item.path("order").asInt(order);
                String html = item.path("html").asText("");
                if (html.isBlank() && item.isTextual()) html = item.asText("");
                page.put("title", title);
                page.put("order", ord);
                page.put("html", cleanHtml(html, platform));
                out.add(page);
                order++;
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("多页 JSON 二次清洗失败，返回原始数组片段: {}", e.getMessage());
            return json;
        }
    }

    private static String stripCodeFence(String s) {
        if (s.startsWith("```html") || s.startsWith("```HTML")) {
            s = s.substring(s.indexOf('\n') >= 0 ? s.indexOf('\n') + 1 : 7);
        } else if (s.startsWith("```json") || s.startsWith("```JSON")) {
            s = s.substring(s.indexOf('\n') >= 0 ? s.indexOf('\n') + 1 : 7);
        } else if (s.startsWith("```")) {
            s = s.substring(s.indexOf('\n') >= 0 ? s.indexOf('\n') + 1 : 3);
        }
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        return s.trim();
    }

    private static boolean looksLikeFullDocument(String html) {
        String lower = html.toLowerCase();
        return lower.contains("<html") || lower.contains("<!doctype");
    }

    private static boolean hasSubstantialCss(String html) {
        String lower = html.toLowerCase();
        int styleOpen = lower.indexOf("<style");
        if (styleOpen < 0) {
            // 没有任何 style，但有大量 class 时视为样式不足
            return !lower.contains("class=");
        }
        int styleClose = lower.indexOf("</style>", styleOpen);
        if (styleClose < 0) return false;
        String css = html.substring(styleOpen, styleClose);
        // 过滤掉几乎空的 style
        String compact = css.replaceAll("\\s+", "");
        return compact.length() > 80 && (compact.contains("{") || compact.contains(":"));
    }

    private static String wrapAsDocument(String fragment) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>原型预览</title>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(fragment);
    }

    private static String addPlatformMarker(String html, Platform platform) {
        if (html == null || html.isBlank() || platform == null) return html;
        String lower = html.toLowerCase();
        int bodyOpen = lower.indexOf("<body");
        if (bodyOpen < 0) return html;
        int bodyTagEnd = html.indexOf('>', bodyOpen);
        if (bodyTagEnd < 0) return html;

        String bodyTag = html.substring(bodyOpen, bodyTagEnd + 1);
        String platformName = platform.name();
        if (!bodyTag.toLowerCase().contains("data-proto-platform=")) {
            bodyTag = bodyTag.substring(0, bodyTag.length() - 1)
                    + " data-proto-platform=\"" + platformName + "\">";
        }
        if (bodyTag.toLowerCase().contains("class=")) {
            bodyTag = bodyTag.replaceFirst("class\\s*=\\s*\"([^\"]*)\"",
                    "class=\"$1 proto-platform-" + platformName.toLowerCase() + "\"");
        } else {
            bodyTag = bodyTag.substring(0, bodyTag.length() - 1)
                    + " class=\"proto-platform-" + platformName.toLowerCase() + "\">";
        }
        return html.substring(0, bodyOpen) + bodyTag + html.substring(bodyTagEnd + 1);
    }

    private static String injectBaseStyles(String html) {
        String base = """
                <style data-proto-fallback>
                *,*::before,*::after{box-sizing:border-box}
                body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,"Noto Sans SC",sans-serif;background:#f5f7fa;color:#303133;line-height:1.6}
                a{color:#409EFF;text-decoration:none}
                button,.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:8px 16px;border:none;border-radius:8px;background:#409EFF;color:#fff;font-size:14px;cursor:pointer}
                button:hover,.btn:hover{filter:brightness(.95)}
                input,select,textarea{width:100%;padding:8px 12px;border:1px solid #dcdfe6;border-radius:8px;font-size:14px;background:#fff}
                .card,section,main{background:#fff;border-radius:8px;box-shadow:0 1px 4px rgba(0,0,0,.06)}
                header,nav,.header,.navbar{background:#fff;border-bottom:1px solid #e4e7ed}
                .container,.page,.app{max-width:1200px;margin:0 auto;padding:16px}
                h1,h2,h3{margin:0 0 12px;font-weight:600}
                ul{padding-left:18px}
                table{width:100%;border-collapse:collapse}
                th,td{border-bottom:1px solid #ebeef5;padding:10px 8px;text-align:left}
                </style>
                """;
        String lower = html.toLowerCase();
        int headClose = lower.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + base + html.substring(headClose);
        }
        int bodyOpen = lower.indexOf("<body");
        if (bodyOpen >= 0) {
            int bodyTagEnd = html.indexOf('>', bodyOpen);
            if (bodyTagEnd > 0) {
                return html.substring(0, bodyTagEnd + 1) + base + html.substring(bodyTagEnd + 1);
            }
        }
        return base + html;
    }

    private static String repairMalformedCssValues(String html) {
        if (html == null || html.isBlank() || !html.toLowerCase().contains("<style")) return html;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?is)<style([^>]*)>(.*?)</style>");
        java.util.regex.Matcher matcher = pattern.matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String css = matcher.group(2);
            String fixed = repairCssBlock(css);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    "<style" + matcher.group(1) + ">" + fixed + "</style>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String repairCssBlock(String css) {
        if (css == null || css.isBlank()) return css;
        String fixed = css;
        // 部分模型会把 CSS 值里的必要空格吞掉，例如 padding:014px / 018px54px / #fff0%。
        fixed = fixed.replaceAll("(?i)([:\\s,(]0)(\\d+(?:px|rpx|rem|em|vh|vw|%))", "$1 $2");
        fixed = fixed.replaceAll("(?i)(\\d(?:px|rpx|rem|em|vh|vw|%))(\\d)", "$1 $2");
        fixed = fixed.replaceAll("(?i)(#[0-9a-f]{3}|#[0-9a-f]{6})(\\d+%)", "$1 $2");
        fixed = fixed.replaceAll("(?i)\\b(transparent|black|white|red|blue|green)(\\d+%)", "$1 $2");
        fixed = fixed.replaceAll("(?i)\\bat(\\d)", "at $1");
        fixed = fixed.replaceAll("(?i)\\b(background|border-color|border|box-shadow|color|opacity|transform|padding|margin|gap|width|height|min-width|min-height|max-width|max-height)(\\d)", "$1 $2");
        return fixed;
    }

    private static String injectPrototypeQualityStyles(String html, Platform platform) {
        if (html == null || html.isBlank() || html.contains("data-proto-quality-guard")) {
            return html;
        }
        // 固定设计系统 CSS（按平台：APP/PAD→Vant，WEB→Element Plus）
        String designSystem = com.example.aidocumentplatform.ai.prompt.PrototypeDesignSystem.buildDesignSystemCss(platform);
        String guard = """
                <style data-proto-quality-guard>
                :root{--proto-primary:#2457d6;--proto-primary-dark:#1745ba;--proto-bg:#f4f7fb;--proto-panel:#fff;--proto-border:#dfe5ee;--proto-border-strong:#cdd6e3;--proto-text:#13213a;--proto-muted:#66738a;--proto-sidebar:#132947;--proto-sidebar-border:#203654;--proto-panel-shadow:0 5px 18px rgba(24,43,74,.04);--proto-control-shadow:0 2px 5px rgba(20,35,60,.06)}
                html{font-size:16px!important}
                body{min-width:0!important;overflow-x:hidden!important;text-rendering:optimizeLegibility;background:var(--proto-bg)!important;color:var(--proto-text)}
                body,*{letter-spacing:0!important;box-sizing:border-box}
                p,li,td,th,label,input,select,textarea,button,a,span,div{font-size:max(12px,1em)}
                h1,.page-title{font-size:clamp(22px,1.5rem,24px)!important;line-height:1.3!important}
                h2,.section-title{font-size:clamp(18px,1.25rem,20px)!important;line-height:1.35!important}
                h3,.card-title{font-size:16px!important;line-height:1.4!important}
                button,.btn,.btn-primary,.btn-secondary,.btn-text,[role="button"],input[type="button"],input[type="submit"]{min-height:40px!important;line-height:1.2!important;white-space:nowrap!important;border-radius:8px!important;font-weight:600!important;display:inline-flex!important;align-items:center!important;justify-content:center!important;gap:8px!important;padding:0 15px!important;text-align:center!important}
                .btn,.btn-primary,.btn-secondary,.btn-text{padding:0 15px!important}
                .btn-primary{border:1px solid var(--proto-primary)!important;background:var(--proto-primary)!important;color:#fff!important;box-shadow:0 2px 5px rgba(36,87,214,.2)}
                .btn-secondary{border:1px solid var(--proto-border-strong)!important;background:var(--proto-panel)!important;color:var(--proto-text)!important}
                .btn-text{border:0!important;background:transparent!important;color:var(--proto-primary)!important;padding-inline:6px!important}
                .btn-primary:hover{background:var(--proto-primary-dark)!important}
                button:active,.btn:active,.btn-primary:active,.btn-secondary:active{transform:scale(.98)}
                input,select,textarea{min-height:38px!important;line-height:1.4!important;font-size:14px!important;border-radius:8px}
                table{width:100%!important;border-collapse:collapse!important;table-layout:auto}
                th,td{height:48px!important;min-height:48px!important;padding:12px 20px!important;vertical-align:middle!important;word-break:break-word!important;font-size:14px!important}
                th{font-size:13px!important;font-weight:600!important;color:var(--proto-muted)}
                td:first-child,th:first-child{min-width:56px!important;font-size:13px!important}
                img,svg,canvas,video{max-width:100%;height:auto}
                svg{max-width:28px!important;max-height:28px!important}
                .app-shell,.page-shell,.workspace-shell,.layout-shell{display:flex;min-height:auto;background:var(--proto-bg);color:var(--proto-text)}
                body[data-proto-platform="WEB"] .app-shell,body[data-proto-platform="WEB"] .page-shell,body[data-proto-platform="WEB"] .workspace-shell,body[data-proto-platform="WEB"] .layout-shell{min-height:100vh}
                .app-header,header,.header,.topbar,.top-bar{min-height:72px!important;padding-left:30px!important;padding-right:30px!important;display:flex;align-items:center;justify-content:space-between;gap:20px;border-bottom:1px solid var(--proto-border);background:rgba(255,255,255,.92)}
                .header-title,.page-heading{display:flex;flex-direction:column;gap:4px}
                .header-actions,.toolbar,.filter-bar,.action-bar,.form-actions,.button-row,.table-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
                .page-title{margin:0!important;font-size:24px!important}
                .page-description,.muted,.description{color:var(--proto-muted)!important;font-size:13px!important;line-height:1.6!important}
                .card,.panel,.section,section,main,aside,header,nav{min-width:0}
                .card,.panel,.table-panel,.metric-card,.form-panel,.detail-panel,.summary-panel,.modal-card,.drawer-panel,[class*="card" i],[class*="panel" i]{border-radius:11px!important;box-shadow:var(--proto-panel-shadow)}
                .card,.panel,.table-panel,.form-panel,.content-panel,.detail-panel,.side-panel,.summary-panel,.summary-card,.info-card,.upload-card,.upload-zone,.dropzone,.step-card,.flow-card,.process-card,.config-card,.setting-card,.feature-card{padding:20px!important}
                [class*="card" i],[class*="panel" i],[class*="summary" i],[class*="notice" i],[class*="alert" i],[class*="upload" i],[class*="dropzone" i],[class*="config" i],[class*="setting" i],[class*="detail" i],[class*="feature" i],[class*="modal" i],[class*="drawer" i]{padding:20px!important}
                .metric-card,.stat-card,.kpi-card{padding:18px!important}
                [class*="metric" i],[class*="stat" i],[class*="kpi" i]{padding:18px!important}
                .metric-grid,.stats-grid,.kpi-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px!important}
                .step-card,.flow-card,.process-card,.wizard-step,.step-item,.process-item,.flow-item,.stage-card,.stage-item{padding:14px 16px!important}
                [class*="step" i],[class*="phase" i],[class*="stage" i],[class*="process" i],[class*="flow" i],[class*="rule" i],[class*="wizard" i]{padding:14px 16px!important}
                .steps,.step-list,.process-list,.flow-list,.stage-list,.wizard-list,[class*="steps" i],[class*="step-list" i],[class*="process-list" i]{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px!important}
                .steps > *,.step-list > *,.process-list > *,.flow-list > *,.stage-list > *,.wizard-list > *{padding:14px 16px!important}
                .step-card > * + *,.flow-card > * + *,.process-card > * + *,.wizard-step > * + *,.step-item > * + *,.process-item > * + *,.flow-item > * + *,.stage-card > * + *,.stage-item > * + *,[class*="step" i] > * + *,[class*="phase" i] > * + *,[class*="stage" i] > * + *,[class*="process" i] > * + *,[class*="flow" i] > * + *,[class*="rule" i] > * + *,[class*="wizard" i] > * + *{margin-top:6px!important}
                .card > h1:first-child,.card > h2:first-child,.card > h3:first-child,.panel > h1:first-child,.panel > h2:first-child,.panel > h3:first-child,.table-panel > h1:first-child,.table-panel > h2:first-child,.table-panel > h3:first-child,[class*="card" i] > h1:first-child,[class*="card" i] > h2:first-child,[class*="card" i] > h3:first-child,[class*="panel" i] > h1:first-child,[class*="panel" i] > h2:first-child,[class*="panel" i] > h3:first-child{margin-top:0!important}
                .card > p,.panel > p,.summary-card > p,.info-card > p,.step-card > p,.flow-card > p,.process-card > p,[class*="card" i] > p,[class*="panel" i] > p,[class*="summary" i] > p,[class*="notice" i] > p,[class*="step" i] > p,[class*="phase" i] > p,[class*="stage" i] > p,[class*="process" i] > p,[class*="flow" i] > p,[class*="rule" i] > p{line-height:1.6!important}
                .form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px!important}
                .form-group,.form-item,.field-group,.input-group{display:flex;flex-direction:column;gap:8px!important;margin-bottom:18px!important}
                .toolbar,.filter-bar,.action-bar,.form-actions,.button-row{gap:10px!important}
                .table-panel{padding:20px!important}
                .table-scroll,.table-wrapper{overflow-x:auto;margin:16px -20px -20px}
                .data-table{width:100%!important;min-width:720px;border-collapse:collapse!important}
                .action-cell,.table-actions{display:flex!important;align-items:center!important;gap:8px!important;justify-content:flex-start!important}
                table button,table .btn,table [role="button"],td button,td .btn,td [role="button"]{height:40px!important;min-height:40px!important;width:88px!important;min-width:88px!important;max-width:88px!important;padding:0 12px!important;display:inline-flex!important;align-items:center!important;justify-content:center!important;text-align:center!important}
                td:last-child,th:last-child{white-space:nowrap}
                .icon,.menu-icon,.status-icon,.cell-media,.grid-icon,.list-item-icon{width:18px!important;height:18px!important;flex:0 0 auto!important;display:inline-flex!important;align-items:center!important;justify-content:center!important;text-align:center!important;line-height:1!important}
                .sidebar,.side-bar,.sidenav,.side-nav,.nav-sidebar,.menu-sidebar,.app-sidebar,.layout-sidebar{width:248px!important;max-width:248px!important;flex:0 0 248px!important;flex-shrink:0!important}
                .sidebar:not(.light),.side-bar:not(.light),.sidenav:not(.light),.side-nav:not(.light),.nav-sidebar:not(.light),.menu-sidebar:not(.light),.app-sidebar:not(.light),.layout-sidebar:not(.light){background:var(--proto-sidebar);border-right:1px solid var(--proto-sidebar-border)}
                .sidebar > nav,.side-bar > nav,.sidenav > nav,.side-nav > nav,.nav-sidebar > nav,.menu-sidebar > nav,.app-sidebar > nav,.layout-sidebar > nav,.nav-list,.navList,.menu-list,.sidebar-menu{padding:22px 12px!important}
                .sidebar .brand,.side-bar .brand,.sidenav .brand,.side-nav .brand,.nav-sidebar .brand,.menu-sidebar .brand,.app-sidebar .brand,.layout-sidebar .brand,.sidebar-header,.brand-area,.logo-area{min-height:76px!important;padding-left:20px!important;padding-right:20px!important}
                .sidebar p,.side-bar p,.sidenav p,.side-nav p,.nav-sidebar p,.menu-sidebar p,.app-sidebar p,.layout-sidebar p{margin-left:10px;margin-right:10px}
                .sidebar a,.sidebar button,.side-bar a,.side-bar button,.sidenav a,.sidenav button,.side-nav a,.side-nav button,.nav-item,.menu-item{min-height:42px!important;font-size:14px!important;padding:11px 12px!important;border-radius:8px!important}
                .main,.main-content,.page-main,.content,.workspace,.dashboard-main{flex:1 1 auto!important;min-width:0!important}
                .content,.workspace,.dashboard-main,.main-content,.page-main{padding:26px 30px 40px}
                .auth-shell{display:grid;grid-template-columns:minmax(0,1fr) minmax(360px,520px);min-height:100vh;background:var(--proto-bg)}
                .auth-visual,.login-visual,.brand-panel,.intro-panel,.left-panel{padding:48px!important}
                .auth-card,.login-card,.register-card,.signin-card{width:min(100%,420px)!important;max-width:420px!important;padding:32px!important}
                .auth-card button,.login-card button,.register-card button,.signin-card button{min-height:40px!important}
                .empty-state,.error-state,.loading-state,.center-state,.success-banner{padding:30px!important;text-align:center}
                .tag,.badge,.status,.status-tag{min-height:24px!important;font-size:12px!important;padding:2px 9px!important;border-radius:999px!important;display:inline-flex;align-items:center;justify-content:center}
                @media (max-width: 768px){
                  body{overflow-x:hidden!important}
                  .container,.page,.app,main{max-width:100%!important;min-width:0!important}
                  button,.btn,[role="button"]{min-height:36px!important}
                  .app-shell,.page-shell,.workspace-shell,.auth-shell{display:block!important}
                  .sidebar,.side-bar,.sidenav,.side-nav,.nav-sidebar,.menu-sidebar,.app-sidebar,.layout-sidebar{width:100%!important;max-width:100%!important;flex-basis:auto!important}
                  header,.header,.topbar,.top-bar,.app-header{min-height:64px!important;padding-left:16px!important;padding-right:16px!important}
                  .content,.workspace,.dashboard-main,.main-content,.page-main{padding:16px!important}
                  .form-grid,.metric-grid,.stats-grid,.kpi-grid{grid-template-columns:1fr!important}
                  .auth-visual,.login-visual,.brand-panel,.intro-panel,.left-panel{padding:24px!important}
                  .auth-card,.login-card,.register-card,.signin-card{padding:24px!important}
                }
                </style>
                """;
        String platformGuard = buildPlatformQualityGuard(platform);
        String lower = html.toLowerCase();
        int headClose = lower.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + designSystem + guard + platformGuard + html.substring(headClose);
        }
        int bodyOpen = lower.indexOf("<body");
        if (bodyOpen >= 0) {
            int bodyTagEnd = html.indexOf('>', bodyOpen);
            if (bodyTagEnd > 0) {
                return html.substring(0, bodyTagEnd + 1) + designSystem + guard + platformGuard + html.substring(bodyTagEnd + 1);
            }
        }
        return designSystem + guard + platformGuard + html;
    }

    private static String buildPlatformQualityGuard(Platform platform) {
        if (platform == null) return "";
        return switch (platform) {
            case APP, MINI_PROGRAM -> """
                    <style data-proto-platform-guard>
                    body[data-proto-platform="APP"],body[data-proto-platform="MINI_PROGRAM"]{background:#eef4f7!important;--proto-mobile-primary:#0bb6c7;--proto-mobile-bg:#f7f9fb;--proto-mobile-card:#fff;--proto-mobile-text:#111827;--proto-mobile-muted:#6b7280}
                    body[data-proto-platform="APP"] .app-shell,body[data-proto-platform="APP"] .page-shell,body[data-proto-platform="APP"] .workspace-shell,
                    body[data-proto-platform="MINI_PROGRAM"] .app-shell,body[data-proto-platform="MINI_PROGRAM"] .page-shell,body[data-proto-platform="MINI_PROGRAM"] .workspace-shell{display:block!important;width:min(390px,100vw)!important;max-width:390px!important;min-height:auto!important;margin:0 auto!important;background:var(--proto-mobile-bg)!important;color:var(--proto-mobile-text)!important;overflow-x:hidden!important}
                    body[data-proto-platform="APP"] .sidebar:not(.bottom-tab):not(.mobile-tabbar):not(.tabbar),body[data-proto-platform="APP"] .side-bar,body[data-proto-platform="APP"] .sidenav,body[data-proto-platform="APP"] .side-nav,body[data-proto-platform="APP"] .nav-sidebar,body[data-proto-platform="APP"] .menu-sidebar,
                    body[data-proto-platform="MINI_PROGRAM"] .sidebar:not(.bottom-tab):not(.mobile-tabbar):not(.tabbar),body[data-proto-platform="MINI_PROGRAM"] .side-bar,body[data-proto-platform="MINI_PROGRAM"] .sidenav,body[data-proto-platform="MINI_PROGRAM"] .side-nav,body[data-proto-platform="MINI_PROGRAM"] .nav-sidebar,body[data-proto-platform="MINI_PROGRAM"] .menu-sidebar{display:none!important}
                    body[data-proto-platform="APP"] header,body[data-proto-platform="APP"] .header,body[data-proto-platform="APP"] .app-header,body[data-proto-platform="APP"] .mobile-header,body[data-proto-platform="APP"] .app-navbar,
                    body[data-proto-platform="MINI_PROGRAM"] header,body[data-proto-platform="MINI_PROGRAM"] .header,body[data-proto-platform="MINI_PROGRAM"] .app-header,body[data-proto-platform="MINI_PROGRAM"] .mobile-header,body[data-proto-platform="MINI_PROGRAM"] .app-navbar{min-height:52px!important;padding:env(safe-area-inset-top) 16px 0!important;display:flex!important;align-items:center!important;gap:10px!important}
                body[data-proto-platform="APP"] main,body[data-proto-platform="APP"] .main,body[data-proto-platform="APP"] .page-main,body[data-proto-platform="APP"] .content,body[data-proto-platform="APP"] .mobile-content,
                body[data-proto-platform="MINI_PROGRAM"] main,body[data-proto-platform="MINI_PROGRAM"] .main,body[data-proto-platform="MINI_PROGRAM"] .page-main,body[data-proto-platform="MINI_PROGRAM"] .content,body[data-proto-platform="MINI_PROGRAM"] .mobile-content{padding:16px!important}
                body[data-proto-platform="APP"]:has(.bottom-tab) main,body[data-proto-platform="APP"]:has(.mobile-tabbar) main,body[data-proto-platform="APP"]:has(.tabbar) main,
                body[data-proto-platform="MINI_PROGRAM"]:has(.bottom-tab) main,body[data-proto-platform="MINI_PROGRAM"]:has(.mobile-tabbar) main,body[data-proto-platform="MINI_PROGRAM"]:has(.tabbar) main{padding-bottom:calc(72px + env(safe-area-inset-bottom))!important}
                body[data-proto-platform="APP"]:has(.bottom-nav) main,body[data-proto-platform="APP"]:has(.bottom-navigation) main,body[data-proto-platform="APP"]:has(.tab-bar) main,body[data-proto-platform="APP"]:has(.tab-nav) main,
                body[data-proto-platform="MINI_PROGRAM"]:has(.bottom-nav) main,body[data-proto-platform="MINI_PROGRAM"]:has(.bottom-navigation) main,body[data-proto-platform="MINI_PROGRAM"]:has(.tab-bar) main,body[data-proto-platform="MINI_PROGRAM"]:has(.tab-nav) main{padding-bottom:calc(72px + env(safe-area-inset-bottom))!important}
                    body[data-proto-platform="APP"] .form-grid,body[data-proto-platform="APP"] .metric-grid,body[data-proto-platform="APP"] .stats-grid,body[data-proto-platform="APP"] .kpi-grid,
                    body[data-proto-platform="MINI_PROGRAM"] .form-grid,body[data-proto-platform="MINI_PROGRAM"] .metric-grid,body[data-proto-platform="MINI_PROGRAM"] .stats-grid,body[data-proto-platform="MINI_PROGRAM"] .kpi-grid{grid-template-columns:1fr!important}
                    body[data-proto-platform="APP"] button,body[data-proto-platform="APP"] .btn,body[data-proto-platform="APP"] [role="button"],body[data-proto-platform="APP"] input[type="button"],body[data-proto-platform="APP"] input[type="submit"],
                    body[data-proto-platform="MINI_PROGRAM"] button,body[data-proto-platform="MINI_PROGRAM"] .btn,body[data-proto-platform="MINI_PROGRAM"] [role="button"],body[data-proto-platform="MINI_PROGRAM"] input[type="button"],body[data-proto-platform="MINI_PROGRAM"] input[type="submit"]{min-height:44px!important;border-radius:10px!important;font-size:15px!important}
                    body[data-proto-platform="APP"] .btn-primary,body[data-proto-platform="APP"] button.primary,body[data-proto-platform="APP"] .primary-btn,
                    body[data-proto-platform="MINI_PROGRAM"] .btn-primary,body[data-proto-platform="MINI_PROGRAM"] button.primary,body[data-proto-platform="MINI_PROGRAM"] .primary-btn{background:var(--proto-mobile-primary)!important;border-color:var(--proto-mobile-primary)!important;color:#fff!important;box-shadow:0 8px 20px rgba(11,182,199,.22)!important}
                    body[data-proto-platform="APP"] input,body[data-proto-platform="APP"] select,body[data-proto-platform="APP"] textarea,
                    body[data-proto-platform="MINI_PROGRAM"] input,body[data-proto-platform="MINI_PROGRAM"] select,body[data-proto-platform="MINI_PROGRAM"] textarea{min-height:44px!important;font-size:15px!important}
                body[data-proto-platform="APP"] .card,body[data-proto-platform="APP"] .panel,body[data-proto-platform="APP"] .list-item,body[data-proto-platform="APP"] .cell,
                body[data-proto-platform="MINI_PROGRAM"] .card,body[data-proto-platform="MINI_PROGRAM"] .panel,body[data-proto-platform="MINI_PROGRAM"] .list-item,body[data-proto-platform="MINI_PROGRAM"] .cell{padding:14px 16px!important;border-radius:14px!important;background:var(--proto-mobile-card)!important;box-shadow:0 2px 10px rgba(15,35,55,.06)!important}
                body[data-proto-platform="APP"] .step-card,body[data-proto-platform="APP"] .step-item,body[data-proto-platform="APP"] .flow-card,body[data-proto-platform="APP"] .process-card,
                body[data-proto-platform="MINI_PROGRAM"] .step-card,body[data-proto-platform="MINI_PROGRAM"] .step-item,body[data-proto-platform="MINI_PROGRAM"] .flow-card,body[data-proto-platform="MINI_PROGRAM"] .process-card{padding:12px 14px!important}
                    body[data-proto-platform="APP"] .bottom-tab,body[data-proto-platform="APP"] .mobile-tabbar,body[data-proto-platform="APP"] .tabbar,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar,body[data-proto-platform="MINI_PROGRAM"] .tabbar{display:flex!important;flex-direction:row!important;align-items:stretch!important;justify-content:space-between!important;flex-wrap:nowrap!important;overflow:hidden!important;position:fixed!important;left:50%!important;right:auto!important;bottom:0!important;transform:translateX(-50%)!important;width:min(390px,100vw)!important;max-width:390px!important;height:calc(60px + env(safe-area-inset-bottom))!important;padding:6px 8px env(safe-area-inset-bottom)!important;background:rgba(255,255,255,.96)!important;border-top:1px solid rgba(17,24,39,.08)!important;box-shadow:0 -8px 24px rgba(15,35,55,.08)!important;border-radius:0!important;z-index:20!important}
                    body[data-proto-platform="APP"] .bottom-tab .nav-list,body[data-proto-platform="APP"] .mobile-tabbar .nav-list,body[data-proto-platform="APP"] .tabbar .nav-list,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab .nav-list,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar .nav-list,body[data-proto-platform="MINI_PROGRAM"] .tabbar .nav-list{display:contents!important;padding:0!important}
                    body[data-proto-platform="APP"] .bottom-tab > *,body[data-proto-platform="APP"] .mobile-tabbar > *,body[data-proto-platform="APP"] .tabbar > *,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab > *,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar > *,body[data-proto-platform="MINI_PROGRAM"] .tabbar > *,
                    body[data-proto-platform="APP"] .bottom-tab .tab-item,body[data-proto-platform="APP"] .mobile-tabbar .tab-item,body[data-proto-platform="APP"] .tabbar .tab-item,body[data-proto-platform="APP"] .bottom-tab .nav-item,body[data-proto-platform="APP"] .mobile-tabbar .nav-item,body[data-proto-platform="APP"] .tabbar .nav-item,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab .tab-item,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar .tab-item,body[data-proto-platform="MINI_PROGRAM"] .tabbar .tab-item,body[data-proto-platform="MINI_PROGRAM"] .bottom-tab .nav-item,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar .nav-item,body[data-proto-platform="MINI_PROGRAM"] .tabbar .nav-item{flex:1 1 0%!important;min-width:0!important;max-width:none!important;overflow:hidden!important}
                    body[data-proto-platform="APP"] .bottom-tab button,body[data-proto-platform="APP"] .mobile-tabbar button,body[data-proto-platform="APP"] .tabbar button,body[data-proto-platform="APP"] .bottom-tab a,body[data-proto-platform="APP"] .mobile-tabbar a,body[data-proto-platform="APP"] .tabbar a,body[data-proto-platform="APP"] .bottom-tab .tab-item,body[data-proto-platform="APP"] .mobile-tabbar .tab-item,body[data-proto-platform="APP"] .tabbar .tab-item,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab button,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar button,body[data-proto-platform="MINI_PROGRAM"] .tabbar button,body[data-proto-platform="MINI_PROGRAM"] .bottom-tab a,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar a,body[data-proto-platform="MINI_PROGRAM"] .tabbar a,body[data-proto-platform="MINI_PROGRAM"] .bottom-tab .tab-item,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar .tab-item,body[data-proto-platform="MINI_PROGRAM"] .tabbar .tab-item{min-width:0!important;width:auto!important;height:48px!important;min-height:48px!important;padding:4px 2px!important;background:transparent!important;box-shadow:none!important;color:var(--proto-mobile-muted)!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:2px!important;white-space:nowrap!important;overflow:hidden!important;text-align:center!important}
                    body[data-proto-platform="APP"] .bottom-tab .tab-label,body[data-proto-platform="APP"] .mobile-tabbar .tab-label,body[data-proto-platform="APP"] .tabbar .tab-label,body[data-proto-platform="APP"] .bottom-tab span,body[data-proto-platform="APP"] .mobile-tabbar span,body[data-proto-platform="APP"] .tabbar span,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab .tab-label,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar .tab-label,body[data-proto-platform="MINI_PROGRAM"] .tabbar .tab-label,body[data-proto-platform="MINI_PROGRAM"] .bottom-tab span,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar span,body[data-proto-platform="MINI_PROGRAM"] .tabbar span{display:block!important;max-width:100%!important;min-width:0!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;font-size:10px!important;line-height:1.1!important}
                    body[data-proto-platform="APP"] .bottom-tab button.active,body[data-proto-platform="APP"] .mobile-tabbar button.active,body[data-proto-platform="APP"] .tabbar button.active,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab button.active,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar button.active,body[data-proto-platform="MINI_PROGRAM"] .tabbar button.active{color:var(--proto-mobile-primary)!important}
                    body[data-proto-platform="APP"] .bottom-nav,body[data-proto-platform="APP"] .bottom-navigation,body[data-proto-platform="APP"] .tab-bar,body[data-proto-platform="APP"] .tab-nav,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-nav,body[data-proto-platform="MINI_PROGRAM"] .bottom-navigation,body[data-proto-platform="MINI_PROGRAM"] .tab-bar,body[data-proto-platform="MINI_PROGRAM"] .tab-nav{display:flex!important;flex-direction:row!important;align-items:stretch!important;justify-content:space-between!important;flex-wrap:nowrap!important;overflow:hidden!important;position:fixed!important;left:50%!important;right:auto!important;bottom:0!important;transform:translateX(-50%)!important;width:min(390px,100vw)!important;max-width:390px!important;height:calc(60px + env(safe-area-inset-bottom))!important;padding:6px 8px env(safe-area-inset-bottom)!important;background:rgba(255,255,255,.96)!important;border-top:1px solid rgba(17,24,39,.08)!important;box-shadow:0 -8px 24px rgba(15,35,55,.08)!important;z-index:20!important}
                    body[data-proto-platform="APP"] .bottom-nav > *,body[data-proto-platform="APP"] .bottom-navigation > *,body[data-proto-platform="APP"] .tab-bar > *,body[data-proto-platform="APP"] .tab-nav > *,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-nav > *,body[data-proto-platform="MINI_PROGRAM"] .bottom-navigation > *,body[data-proto-platform="MINI_PROGRAM"] .tab-bar > *,body[data-proto-platform="MINI_PROGRAM"] .tab-nav > *{flex:1 1 0%!important;min-width:0!important;max-width:none!important;height:48px!important;min-height:48px!important;padding:4px 2px!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:2px!important;white-space:nowrap!important;overflow:hidden!important;text-align:center!important}
                    body[data-proto-platform="APP"] .bottom-nav span,body[data-proto-platform="APP"] .bottom-navigation span,body[data-proto-platform="APP"] .tab-bar span,body[data-proto-platform="APP"] .tab-nav span,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-nav span,body[data-proto-platform="MINI_PROGRAM"] .bottom-navigation span,body[data-proto-platform="MINI_PROGRAM"] .tab-bar span,body[data-proto-platform="MINI_PROGRAM"] .tab-nav span{display:block!important;max-width:100%!important;min-width:0!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;font-size:10px!important;line-height:1.1!important}
                    body[data-proto-platform="APP"] .bottom-tab a.active,body[data-proto-platform="APP"] .mobile-tabbar a.active,body[data-proto-platform="APP"] .tabbar a.active,body[data-proto-platform="APP"] .bottom-tab .tab-item.active,body[data-proto-platform="APP"] .mobile-tabbar .tab-item.active,body[data-proto-platform="APP"] .tabbar .tab-item.active,body[data-proto-platform="APP"] .bottom-nav .active,body[data-proto-platform="APP"] .bottom-navigation .active,body[data-proto-platform="APP"] .tab-bar .active,body[data-proto-platform="APP"] .tab-nav .active,
                    body[data-proto-platform="MINI_PROGRAM"] .bottom-tab a.active,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar a.active,body[data-proto-platform="MINI_PROGRAM"] .tabbar a.active,body[data-proto-platform="MINI_PROGRAM"] .bottom-tab .tab-item.active,body[data-proto-platform="MINI_PROGRAM"] .mobile-tabbar .tab-item.active,body[data-proto-platform="MINI_PROGRAM"] .tabbar .tab-item.active,body[data-proto-platform="MINI_PROGRAM"] .bottom-nav .active,body[data-proto-platform="MINI_PROGRAM"] .bottom-navigation .active,body[data-proto-platform="MINI_PROGRAM"] .tab-bar .active,body[data-proto-platform="MINI_PROGRAM"] .tab-nav .active{color:var(--proto-mobile-primary)!important}
                    body[data-proto-platform="APP"] table,body[data-proto-platform="MINI_PROGRAM"] table{display:block!important;overflow-x:auto!important;min-width:0!important}
                    </style>
                    """;
            case PAD -> """
                    <style data-proto-platform-guard>
                    body[data-proto-platform="PAD"]{background:#eef2f7!important}
                    body[data-proto-platform="PAD"] .app-shell,body[data-proto-platform="PAD"] .page-shell,body[data-proto-platform="PAD"] .workspace-shell,body[data-proto-platform="PAD"] .tablet-shell,body[data-proto-platform="PAD"] .pad-shell{width:min(1024px,100vw)!important;max-width:1024px!important;min-height:100vh!important;margin:0 auto!important;background:#f4f7fb!important}
                    body[data-proto-platform="PAD"] .sidebar,body[data-proto-platform="PAD"] .side-bar,body[data-proto-platform="PAD"] .sidenav,body[data-proto-platform="PAD"] .side-nav,body[data-proto-platform="PAD"] .nav-sidebar,body[data-proto-platform="PAD"] .menu-sidebar{width:260px!important;max-width:280px!important;flex-basis:260px!important}
                    body[data-proto-platform="PAD"] header,body[data-proto-platform="PAD"] .header,body[data-proto-platform="PAD"] .app-header{min-height:68px!important;padding-left:24px!important;padding-right:24px!important}
                    body[data-proto-platform="PAD"] main,body[data-proto-platform="PAD"] .main,body[data-proto-platform="PAD"] .page-main,body[data-proto-platform="PAD"] .content,body[data-proto-platform="PAD"] .workspace{padding:24px!important}
                    body[data-proto-platform="PAD"] .card,body[data-proto-platform="PAD"] .panel,body[data-proto-platform="PAD"] .detail-panel,body[data-proto-platform="PAD"] .summary-panel{padding:22px!important;border-radius:12px!important}
                    body[data-proto-platform="PAD"] button,body[data-proto-platform="PAD"] .btn,body[data-proto-platform="PAD"] [role="button"]{min-height:44px!important}
                    body[data-proto-platform="PAD"] input,body[data-proto-platform="PAD"] select,body[data-proto-platform="PAD"] textarea{min-height:42px!important}
                    body[data-proto-platform="PAD"] .master-detail,body[data-proto-platform="PAD"] .split-view,body[data-proto-platform="PAD"] .two-column{display:grid!important;grid-template-columns:minmax(280px,340px) minmax(0,1fr)!important;gap:24px!important}
                    @media (max-width: 700px){body[data-proto-platform="PAD"] .master-detail,body[data-proto-platform="PAD"] .split-view,body[data-proto-platform="PAD"] .two-column{grid-template-columns:1fr!important}}
                    </style>
                    """;
            case WEB -> "";
        };
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}