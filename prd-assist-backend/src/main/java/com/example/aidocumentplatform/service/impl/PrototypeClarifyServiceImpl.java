package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;
import com.example.aidocumentplatform.model.dto.request.PrototypeClarifyRequest;
import com.example.aidocumentplatform.model.dto.request.PrototypeFinalizeRequest;
import com.example.aidocumentplatform.model.dto.response.PrototypeClarifyResponse;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.service.PrototypeClarifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PrototypeClarifyServiceImpl implements PrototypeClarifyService {

    private static final int MAX_QUESTIONS = 3;
    private static final int MAX_OPTIONS = 4;
    private static final int CLARIFY_MAX_OUTPUT_TOKENS = 4096;
    private static final int CLARIFY_TIMEOUT_SECONDS = 90;

    private static final String SYSTEM_PROMPT = """
            你是原型生成前的需求分析器。你的工作不是套固定页面类型，而是理解用户实际想看到的画面，判断现有描述是否足以生成。

            原则：
            1. 先逐字提取用户已经明确的事实：主体、构图、位置、背景、光效、核心动作、按钮数量、内容密度、禁止项。已经明确的内容绝不重复提问。
            2. 只问会显著改变最终画面的关键缺口，最多 3 题；抓大放小，不问字号、圆角、普通间距等可由设计规范决定的细节。
            3. 问题和选项必须根据本次需求动态生成，禁止固定复用“完整页面/弹窗/局部模块”“专业简洁/科技感”等通用题库。
            4. 如果需求已经清楚，questions 返回空数组，直接生成。
            5. 不要把展示型、沉浸式、角色主视觉需求改造成业务卡片、数据看板或说明页。用户未要求的导航、数据、属性、介绍文案、卡片和图标都应写入 mustNotAdd。
            6. generationBrief 是交给下游生成模型的执行简报，必须忠实、具体、可直接执行，不能只写抽象标签。
            7. 同时判断页面是否必须有一张核心视觉素材。普通表格、表单、后台列表不需要；人物、精灵、商品特写、活动主视觉等没有图片就无法成立的场景才需要。
               上传图片默认只用于参考风格。只有用户明确说“使用上传图片作为主体/实际素材”时，assetPlan.source 才能是 REFERENCE；否则需要新视觉素材时使用 GENERATED。
            8. 单页面使用 assetPlan，assetPlans 返回空数组。多页面且各页需要不同构图、姿态或背景时，使用 assetPlans，最多 3 项；每项必须包含唯一英文 key 和 targetPage。assetPlan 同时返回第一项，兼容旧客户端。

            只输出严格 JSON，不要 markdown。结构：
            {
              "needsClarification": true,
              "intentSummary": "一句话复述用户真正想看到的画面",
              "generationBrief": "平台、体验意图、画面结构、主体位置、必备元素、内容密度、视觉氛围、素材要求、mustNotAdd 的完整简报",
              "assetPlan": {
                "required": true,
                "source": "GENERATED | REFERENCE | NONE",
                "role": "图片在页面中的角色，例如居中的人形精灵主视觉",
                "prompt": "独立图片生成模型可直接执行的完整提示词；不包含按钮、标题、导航等 UI 文字",
                "aspectRatio": "1:1 | 3:4 | 4:3 | 9:16 | 16:9",
                "transparentBackground": true
              },
              "assetPlans": [
                {
                  "key": "lock_screen",
                  "targetPage": "锁屏页",
                  "required": true,
                  "source": "GENERATED",
                  "role": "锁屏页独有的主视觉",
                  "prompt": "独立图片模型可执行的该页素材提示词",
                  "aspectRatio": "9:16",
                  "transparentBackground": false
                }
              ],
              "questions": [
                {
                  "id": "本次需求语义相关的英文短标识",
                  "title": "短标题",
                  "prompt": "具体问题",
                  "options": [
                    {"value":"可直接拼入生成简报的答案", "label":"选项名称", "description":"选择后画面会怎样"}
                  ],
                  "allowCustomInput": true,
                  "customInputPlaceholder": "具体输入提示"
                }
              ]
            }
            """;

    private static final String FINALIZE_SYSTEM_PROMPT = """
            你是原型生成前的最终规划器。用户已经回答了系统提出的关键问题。
            请把原始描述、初步简报和确认答案合并成唯一、无冲突、可直接执行的最终规划。

            规则：
            1. 用户原始描述和最新确认答案优先于初步简报；不得遗漏明确的构图、数量、位置和禁止项。
            2. 重新判断核心视觉素材是否必要，不得机械沿用初步 assetPlan。
            3. 普通后台、表格、表单和纯结构页通常不生图；人物、精灵、商品特写、活动主视觉等没有图片就无法成立时才生图。
            4. 上传图默认仅作风格参考；只有用户明确要求把上传图作为页面实际主体时才使用 REFERENCE。
            5. 图片 prompt 只描述需要生成的视觉素材，不包含按钮、导航、标题或其他 UI 文字。
            6. 不再提问，questions 必须是空数组，needsClarification 必须为 false。
            7. 多页面视觉套装必须重新产出最多 3 个 assetPlans，分别对应不同页面构图；不得因为用户回答完成而把初步图片计划清空。

            只输出严格 JSON，字段与初步分析一致：intentSummary、generationBrief、assetPlan、assetPlans、questions、needsClarification。
            """;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrototypeClarifyServiceImpl(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public PrototypeClarifyResponse clarify(PrototypeClarifyRequest request, Long userId) {
        String description = request.getDescription().trim();
        Platform platform = request.getPlatform() == null ? Platform.APP : request.getPlatform();
        PrototypeType prototypeType = request.getPrototypeType() == null
                ? PrototypeType.SINGLE_PAGE : request.getPrototypeType();
        String userPrompt = """
                分析下面这次原型需求。平台由用户在界面上明确选择，不要反问平台。

                【平台】%s
                【生成模式】%s
                【是否上传风格参考图】%s
                【用户原始描述】
                %s

                先判断描述中已经明确了什么，再决定是否真的需要提问。若用户已明确“背景铺满、主体居中、顶部打光、主体下方一个按钮”之类构图，必须原样写入 generationBrief，并明确禁止自动补充说明卡片、属性数值、导航和长文案。
                """.formatted(platform.name(), prototypeType.name(),
                request.isHasReferenceImage() ? "是" : "否", description);

        try {
            AiRequestContext.setUserId(userId);
            configureLightweightRequest();
            String raw = aiClient.generate(SYSTEM_PROMPT, userPrompt);
            return parseResponse(raw, platform, prototypeType, description,
                    request.isHasReferenceImage(), null);
        } catch (Exception e) {
            log.warn("原型需求 AI 澄清失败，使用保守简报直接生成: platform={}, reason={}", platform, rootMessage(e));
            return fallbackResponse(platform, prototypeType, description, request.isHasReferenceImage());
        } finally {
            AiRequestContext.clear();
        }
    }

    @Override
    public PrototypeClarifyResponse finalizePlan(PrototypeFinalizeRequest request, Long userId) {
        Platform platform = request.getPlatform() == null ? Platform.APP : request.getPlatform();
        PrototypeType prototypeType = request.getPrototypeType() == null
                ? PrototypeType.SINGLE_PAGE : request.getPrototypeType();
        String answers = String.join("\n", request.getClarificationAnswers());
        String userPrompt = """
                【平台】%s
                【生成模式】%s
                【是否上传风格参考图】%s
                【用户原始描述】
                %s

                【初步生成简报】
                %s

                【用户最新确认答案】
                %s
                """.formatted(
                platform.name(),
                prototypeType.name(),
                request.isHasReferenceImage() ? "是" : "否",
                request.getDescription().trim(),
                request.getGenerationBrief() == null ? "" : request.getGenerationBrief().trim(),
                answers);
        try {
            AiRequestContext.setUserId(userId);
            configureLightweightRequest();
            String raw = aiClient.generate(FINALIZE_SYSTEM_PROMPT, userPrompt);
            PrototypeClarifyResponse response = parseResponse(
                    raw, platform, prototypeType, request.getDescription(),
                    request.isHasReferenceImage(), request.getAssetPlans());
            response.setAssetPlan(ensureRequiredVisualAsset(
                    response.getAssetPlan(),
                    request.getAssetPlan(),
                    request.getDescription(),
                    response.getGenerationBrief(),
                    request.isHasReferenceImage()));
            response.setAssetPlans(ensureMultiPageAssetPlans(
                    response.getAssetPlans(), request.getAssetPlans(), response.getAssetPlan(),
                    prototypeType, request.getDescription(), response.getGenerationBrief()));
            if (!response.getAssetPlans().isEmpty()) {
                response.setAssetPlan(response.getAssetPlans().get(0));
            }
            response.setNeedsClarification(false);
            response.setQuestions(List.of());
            return response;
        } catch (Exception e) {
            log.warn("原型最终规划失败，使用初步简报与确认答案合并结果: platform={}, reason={}",
                    platform, rootMessage(e));
            return fallbackFinalPlan(request, platform);
        } finally {
            AiRequestContext.clear();
        }
    }

    private PrototypeClarifyResponse fallbackFinalPlan(PrototypeFinalizeRequest request, Platform platform) {
        String base = request.getGenerationBrief() == null ? "" : request.getGenerationBrief().trim();
        String brief = base + "\n用户最新确认答案（高优先级）：\n- "
                + String.join("\n- ", request.getClarificationAnswers());
        return PrototypeClarifyResponse.builder()
                .needsClarification(false)
                .intentSummary("按用户原始描述与最新确认答案生成")
                .generationBrief(brief)
                .assetPlan(request.getAssetPlan() == null ? noAssetPlan() : request.getAssetPlan())
                .assetPlans(request.getAssetPlans() == null ? List.of() : request.getAssetPlans())
                .questions(List.of())
                .build();
    }

    private void configureLightweightRequest() {
        AiRequestContext.setRequestOptions(
                "low",
                CLARIFY_MAX_OUTPUT_TOKENS,
                CLARIFY_TIMEOUT_SECONDS,
                0,
                false);
    }

    private PrototypeClarifyResponse parseResponse(
            String raw,
            Platform platform,
            PrototypeType prototypeType,
            String description,
            boolean hasReferenceImage,
            List<PrototypeAssetPlan> previousPlans) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        String summary = text(root, "intentSummary");
        String brief = text(root, "generationBrief");
        if (summary.isBlank() || brief.isBlank()) {
            throw new IllegalArgumentException("AI 澄清结果缺少 intentSummary 或 generationBrief");
        }

        List<PrototypeClarifyResponse.Question> questions = new ArrayList<>();
        JsonNode questionNodes = root.path("questions");
        if (questionNodes.isArray()) {
            for (JsonNode node : questionNodes) {
                if (questions.size() >= MAX_QUESTIONS) break;
                PrototypeClarifyResponse.Question question = parseQuestion(node, questions.size());
                if (question != null) questions.add(question);
            }
        }

        boolean needsClarification = root.path("needsClarification").asBoolean(!questions.isEmpty())
                && !questions.isEmpty();
        if (!needsClarification) questions = List.of();

        PrototypeAssetPlan assetPlan = parseAssetPlan(root.path("assetPlan"), hasReferenceImage);
        assetPlan = ensureRequiredVisualAsset(assetPlan, null, description, brief, hasReferenceImage);
        List<PrototypeAssetPlan> assetPlans = parseAssetPlans(root.path("assetPlans"), hasReferenceImage);
        assetPlans = ensureMultiPageAssetPlans(
                assetPlans, previousPlans, assetPlan, prototypeType, description, brief);
        if (!assetPlans.isEmpty()) assetPlan = assetPlans.get(0);

        return PrototypeClarifyResponse.builder()
                .needsClarification(needsClarification)
                .intentSummary(summary)
                .generationBrief(brief)
                .assetPlan(assetPlan)
                .assetPlans(assetPlans)
                .questions(questions)
                .build();
    }

    private PrototypeAssetPlan parseAssetPlan(JsonNode node, boolean hasReferenceImage) {
        if (node == null || !node.isObject() || !node.path("required").asBoolean(false)) {
            return noAssetPlan();
        }
        String source = text(node, "source").toUpperCase(java.util.Locale.ROOT);
        if ("REFERENCE".equals(source) && !hasReferenceImage) source = "GENERATED";
        if (!"REFERENCE".equals(source) && !"GENERATED".equals(source)) source = "GENERATED";
        String prompt = text(node, "prompt");
        if ("GENERATED".equals(source) && prompt.isBlank()) return noAssetPlan();
        return PrototypeAssetPlan.builder()
                .key(normalizeAssetKey(text(node, "key"), "primary"))
                .targetPage(text(node, "targetPage"))
                .required(true)
                .source(source)
                .role(text(node, "role"))
                .prompt(prompt)
                .aspectRatio(normalizeAspectRatio(text(node, "aspectRatio")))
                .transparentBackground(node.path("transparentBackground").asBoolean(false))
                .build();
    }

    private List<PrototypeAssetPlan> parseAssetPlans(JsonNode node, boolean hasReferenceImage) {
        if (node == null || !node.isArray()) return List.of();
        List<PrototypeAssetPlan> plans = new ArrayList<>();
        for (JsonNode item : node) {
            if (plans.size() >= 3) break;
            PrototypeAssetPlan plan = parseAssetPlan(item, hasReferenceImage);
            if (!plan.isRequired()) continue;
            plan.setKey(normalizeAssetKey(plan.getKey(), "page_" + (plans.size() + 1)));
            plans.add(plan);
        }
        return List.copyOf(plans);
    }

    private List<PrototypeAssetPlan> ensureMultiPageAssetPlans(
            List<PrototypeAssetPlan> planned,
            List<PrototypeAssetPlan> previous,
            PrototypeAssetPlan primary,
            PrototypeType prototypeType,
            String description,
            String brief) {
        if (prototypeType != PrototypeType.MULTI_PAGE) return List.of();
        if (planned != null && !planned.isEmpty()) return normalizePlans(planned);
        if (previous != null && !previous.isEmpty()) {
            log.info("最终规划清空了多页面素材，已恢复初步计划: count={}", previous.size());
            return normalizePlans(previous);
        }
        if (primary == null || !primary.isRequired()) return List.of();

        String requirement = (description == null ? "" : description) + "\n" + (brief == null ? "" : brief);
        List<PageVisual> pages = detectVisualPages(requirement);
        if (pages.size() <= 1) return List.of(copyPlan(primary, "primary", pages.isEmpty() ? "" : pages.get(0).title(), null));

        List<PrototypeAssetPlan> derived = new ArrayList<>();
        for (PageVisual page : pages) {
            if (derived.size() >= 3) break;
            derived.add(copyPlan(
                    primary,
                    page.key(),
                    page.title(),
                    primary.getPrompt() + "\n为“" + page.title() + "”单独生成，构图重点：" + page.direction()));
        }
        log.info("多页面视觉需求缺少 assetPlans，已按已确认页面拆分: pages={}",
                derived.stream().map(PrototypeAssetPlan::getTargetPage).toList());
        return List.copyOf(derived);
    }

    private List<PrototypeAssetPlan> normalizePlans(List<PrototypeAssetPlan> plans) {
        List<PrototypeAssetPlan> normalized = new ArrayList<>();
        for (PrototypeAssetPlan plan : plans) {
            if (plan == null || !plan.isRequired() || normalized.size() >= 3) continue;
            plan.setKey(normalizeAssetKey(plan.getKey(), "page_" + (normalized.size() + 1)));
            normalized.add(plan);
        }
        return List.copyOf(normalized);
    }

    private PrototypeAssetPlan copyPlan(
            PrototypeAssetPlan source, String key, String targetPage, String promptOverride) {
        return PrototypeAssetPlan.builder()
                .key(normalizeAssetKey(key, "primary"))
                .targetPage(targetPage)
                .required(true)
                .source(source.getSource())
                .role(targetPage == null || targetPage.isBlank()
                        ? source.getRole() : targetPage + "的独立视觉素材")
                .prompt(promptOverride == null ? source.getPrompt() : promptOverride)
                .aspectRatio(source.getAspectRatio())
                .transparentBackground(source.isTransparentBackground())
                .build();
    }

    private List<PageVisual> detectVisualPages(String requirement) {
        List<PageVisual> pages = new ArrayList<>();
        addPageIfMentioned(pages, requirement, "lock_screen", "锁屏页", "锁屏", "时间日期留白与沉浸式壁纸");
        addPageIfMentioned(pages, requirement, "home_screen", "主屏页", "主屏", "桌面图标、组件与主体互动");
        if (!requirement.contains("主屏")) {
            addPageIfMentioned(pages, requirement, "home_screen", "主页面", "主页面", "页面核心内容与主体互动");
        }
        addPageIfMentioned(pages, requirement, "widget_screen", "组件页", "组件", "主体与组件卡片边缘自然融合");
        addPageIfMentioned(pages, requirement, "charging_screen", "充电页", "充电", "围绕充电状态形成动态主视觉");
        return pages;
    }

    private void addPageIfMentioned(
            List<PageVisual> pages, String requirement, String key, String title, String needle, String direction) {
        if (requirement.contains(needle) && pages.stream().noneMatch(item -> item.key().equals(key))) {
            pages.add(new PageVisual(key, title, direction));
        }
    }

    private String normalizeAssetKey(String value, String fallback) {
        String key = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.isBlank() ? fallback : key;
    }

    private record PageVisual(String key, String title, String direction) {
    }

    private String normalizeAspectRatio(String value) {
        return switch (value) {
            case "3:4", "4:3", "9:16", "16:9" -> value;
            default -> "1:1";
        };
    }

    private PrototypeAssetPlan noAssetPlan() {
        return PrototypeAssetPlan.builder().required(false).source("NONE").aspectRatio("1:1").build();
    }

    private PrototypeAssetPlan ensureRequiredVisualAsset(
            PrototypeAssetPlan planned,
            PrototypeAssetPlan previous,
            String description,
            String brief,
            boolean hasReferenceImage) {
        if (previous != null && previous.isRequired() && isAutoFallbackPlan(planned)) {
            log.info("最终规划错误清空了必要视觉素材，已恢复初步素材计划: role={}", previous.getRole());
            return previous;
        }
        if (planned != null && planned.isRequired()) return planned;

        String requirement = ((description == null ? "" : description) + "\n"
                + (brief == null ? "" : brief)).toLowerCase(java.util.Locale.ROOT);
        // 只用用户原始描述判断是否需要生图：AI 简报里的视觉关键词多出现在
        // “避免加入人物主视觉、活动海报”这类禁止项中，拿简报匹配会给后台页误配图片
        String descriptionText = (description == null ? "" : description).toLowerCase(java.util.Locale.ROOT);
        if (!requiresVisualAsset(descriptionText)) return planned == null ? noAssetPlan() : planned;

        if (previous != null && previous.isRequired()) {
            log.info("最终规划错误清空了必要视觉素材，已恢复初步素材计划: role={}", previous.getRole());
            return previous;
        }

        boolean fullBackground = containsAny(requirement,
                "壁纸铺满", "背景铺满", "完整壁纸", "壁纸主视觉", "全屏背景");
        String subject = description == null ? "核心视觉主体" : description.trim();
        String prompt = fullBackground
                ? "生成适合手机主题使用的 9:16 竖版完整视觉背景。忠实表现以下需求中的主体、画风与氛围：" + subject
                : "生成可复用于原型页面的独立核心视觉主体，主体完整、轮廓清晰。忠实表现以下需求：" + subject;
        log.info("需求明确依赖视觉素材但 AI 返回 NONE，已自动补全素材计划: fullBackground={}", fullBackground);
        return PrototypeAssetPlan.builder()
                .key("primary")
                .required(true)
                .source(hasReferenceImage && requirement.contains("参考图作为") ? "REFERENCE" : "GENERATED")
                .role(fullBackground ? "手机主题的完整壁纸主视觉" : "可跨页面复用的核心视觉主体")
                .prompt(prompt)
                .aspectRatio(fullBackground ? "9:16" : "3:4")
                .transparentBackground(!fullBackground)
                .build();
    }

    private boolean requiresVisualAsset(String value) {
        return containsAny(value,
                "壁纸", "主题套装", "角色主视觉", "人物主视觉", "精灵主视觉", "商品主视觉",
                "角色展示", "人物展示", "精灵展示", "商品图", "商品图片", "插画主视觉",
                "照片墙", "海报", "头像素材", "每页都出现小猫", "每个页面都出现小猫",
                "每页都有小猫", "每个页面都有小猫", "每页都出现角色", "每个页面都出现角色");
    }

    private boolean isAutoFallbackPlan(PrototypeAssetPlan plan) {
        return plan == null || !plan.isRequired()
                || "可跨页面复用的核心视觉主体".equals(plan.getRole())
                || "手机主题的完整壁纸主视觉".equals(plan.getRole());
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private PrototypeClarifyResponse.Question parseQuestion(JsonNode node, int index) {
        String prompt = text(node, "prompt");
        if (prompt.isBlank()) return null;

        List<PrototypeClarifyResponse.Option> options = new ArrayList<>();
        JsonNode optionNodes = node.path("options");
        if (optionNodes.isArray()) {
            for (JsonNode optionNode : optionNodes) {
                if (options.size() >= MAX_OPTIONS) break;
                String value = text(optionNode, "value");
                String label = text(optionNode, "label");
                if (value.isBlank() || label.isBlank()) continue;
                options.add(PrototypeClarifyResponse.Option.builder()
                        .value(value)
                        .label(label)
                        .description(text(optionNode, "description"))
                        .build());
            }
        }
        if (options.size() < 2) return null;

        String id = text(node, "id");
        String title = text(node, "title");
        String placeholder = text(node, "customInputPlaceholder");
        return PrototypeClarifyResponse.Question.builder()
                .id(id.isBlank() ? "question_" + (index + 1) : id)
                .title(title.isBlank() ? "需要确认" : title)
                .prompt(prompt)
                .options(options)
                .allowCustomInput(true)
                .customInputPlaceholder(placeholder.isBlank() ? "也可以直接写下你的具体要求" : placeholder)
                .build();
    }

    private PrototypeClarifyResponse fallbackResponse(
            Platform platform, PrototypeType prototypeType, String description, boolean hasReferenceImage) {
        String brief = "平台：" + platform.name() + "\n"
                + "用户原始需求（最高优先级）：" + description + "\n"
                + "执行原则：只实现用户明确要求的主体、构图和操作；不要自行补充导航、数据指标、说明卡片、属性数值、长文案或无关功能。\n"
                + "风格来源：" + (hasReferenceImage ? "参考图覆盖默认视觉风格。" : "使用平台默认设计规范，仅补齐可用性细节。");
        // 澄清模型不可用时，仍按关键词兜底判断视觉素材，避免壁纸、角色主视觉等需求因降级而完全失去图片
        PrototypeAssetPlan assetPlan = ensureRequiredVisualAsset(
                null, null, description, brief, hasReferenceImage);
        List<PrototypeAssetPlan> assetPlans = ensureMultiPageAssetPlans(
                List.of(), null, assetPlan, prototypeType, description, brief);
        if (!assetPlans.isEmpty()) assetPlan = assetPlans.get(0);
        return PrototypeClarifyResponse.builder()
                .needsClarification(false)
                .intentSummary("按用户原始描述忠实生成，不添加未要求的业务模块")
                .generationBrief(brief)
                .assetPlan(assetPlan)
                .assetPlans(assetPlans)
                .questions(List.of())
                .build();
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("AI 澄清结果为空");
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("AI 澄清结果不是 JSON 对象");
        return value.substring(start, end + 1);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
