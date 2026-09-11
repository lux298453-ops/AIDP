package com.example.aidocumentplatform;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.model.dto.request.PrototypeClarifyRequest;
import com.example.aidocumentplatform.model.dto.request.PrototypeFinalizeRequest;
import com.example.aidocumentplatform.model.dto.response.PrototypeClarifyResponse;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.service.impl.PrototypeClarifyServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrototypeClarifyServiceImplTest {

    @Test
    void shouldUseLightweightRequestPolicyOnlyDuringClarification() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), anyString())).thenAnswer(invocation -> {
            AiRequestContext.RequestOptions options = AiRequestContext.getRequestOptions();
            assertThat(options).isNotNull();
            assertThat(options.reasoningEffort()).isEqualTo("low");
            assertThat(options.maxOutputTokens()).isEqualTo(4096);
            assertThat(options.timeoutSeconds()).isEqualTo(90);
            assertThat(options.retryCount()).isZero();
            assertThat(options.fallbackEnabled()).isFalse();
            return """
                    {
                      "needsClarification": false,
                      "intentSummary": "简洁的移动端领取页",
                      "generationBrief": "APP 单页；按原始描述生成；不增加无关内容。",
                      "assetPlan": {"required": false, "source": "NONE"},
                      "assetPlans": [],
                      "questions": []
                    }
                    """;
        });

        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.APP);
        request.setDescription("做一个领取页");

        new PrototypeClarifyServiceImpl(aiClient).clarify(request, 1L);

        assertThat(AiRequestContext.getUserId()).isNull();
        assertThat(AiRequestContext.getRequestOptions()).isNull();
    }

    @Test
    void shouldReplanAfterUserAnswers() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("用户最新确认答案"))).thenReturn("""
                {
                  "needsClarification": false,
                  "intentSummary": "最终确定为沉浸式精灵领取页",
                  "generationBrief": "APP 单页；精灵居中；顶部打光；背景铺满；下方仅一个领取按钮；禁止导航和说明卡片。",
                  "assetPlan": {
                    "required": true,
                    "source": "GENERATED",
                    "role": "居中的人形精灵",
                    "prompt": "白发龙角人形精灵完整立绘，蓝白服饰，金色轮廓光",
                    "aspectRatio": "3:4",
                    "transparentBackground": true
                  },
                  "questions": []
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeFinalizeRequest request = new PrototypeFinalizeRequest();
        request.setPlatform(Platform.APP);
        request.setDescription("做一个精灵领取页");
        request.setGenerationBrief("初步：普通卡片页");
        request.setClarificationAnswers(List.of("背景铺满，精灵居中，顶部打光，只保留领取按钮"));

        PrototypeClarifyResponse response = service.finalizePlan(request, 1L);

        assertThat(response.isNeedsClarification()).isFalse();
        assertThat(response.getGenerationBrief()).contains("背景铺满", "仅一个领取按钮", "禁止导航");
        assertThat(response.getAssetPlan().isRequired()).isTrue();
        assertThat(response.getAssetPlan().getPrompt()).contains("白发龙角");
    }

    @Test
    void shouldUseAiGeneratedIntentInsteadOfFixedQuestionCategories() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("精灵展示页"))).thenReturn("""
                {
                  "needsClarification": false,
                  "intentSummary": "沉浸式精灵领取展示页",
                  "generationBrief": "APP 单页；星耀背景铺满；人形精灵居中；顶部一束光照亮精灵；精灵下方仅一个领取按钮；禁止导航、说明卡片、属性数值和长文案。",
                  "assetPlan": {
                    "required": true,
                    "source": "GENERATED",
                    "role": "居中的人形精灵主视觉",
                    "prompt": "白发龙角人形精灵，蓝白中式服装，完整角色立绘，金色星光",
                    "aspectRatio": "3:4",
                    "transparentBackground": true
                  },
                  "questions": []
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.APP);
        request.setDescription("做一个精灵展示页，星耀背景铺满，精灵在正中间，头顶打光，下面只有领取按钮");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.isNeedsClarification()).isFalse();
        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getGenerationBrief())
                .contains("星耀背景铺满", "人形精灵居中", "仅一个领取按钮", "禁止导航、说明卡片");
        assertThat(response.getAssetPlan().isRequired()).isTrue();
        assertThat(response.getAssetPlan().getSource()).isEqualTo("GENERATED");
        assertThat(response.getAssetPlan().getPrompt()).contains("白发龙角人形精灵");
    }

    @Test
    void shouldKeepDynamicQuestionContext() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("获取人形精灵"))).thenReturn("""
                {
                  "needsClarification": true,
                  "intentSummary": "以人形精灵为唯一主视觉的领取页",
                  "generationBrief": "APP 单页；人形精灵居中；下方一个领取按钮；不要说明卡片和属性数值。",
                  "assetPlan": {
                    "required": true,
                    "source": "GENERATED",
                    "role": "人形精灵",
                    "prompt": "完整人形精灵角色立绘",
                    "aspectRatio": "3:4",
                    "transparentBackground": true
                  },
                  "questions": [{
                    "id": "background_atmosphere",
                    "title": "背景氛围",
                    "prompt": "精灵背后的满屏氛围更接近哪一种？",
                    "options": [
                      {"value":"深色星耀背景，金色粒子围绕主体", "label":"星耀夜空", "description":"突出顶部光束和角色轮廓"},
                      {"value":"浅色云海背景，柔和天光照亮主体", "label":"云海天光", "description":"更明亮轻盈"}
                    ],
                    "allowCustomInput": true,
                    "customInputPlaceholder": "也可以描述具体背景"
                  }]
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.APP);
        request.setDescription("做一个获取人形精灵的展示页，精灵居中，下方一个领取按钮");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.isNeedsClarification()).isTrue();
        assertThat(response.getQuestions()).singleElement().satisfies(question -> {
            assertThat(question.getId()).isEqualTo("background_atmosphere");
            assertThat(question.getPrompt()).contains("满屏氛围");
            assertThat(question.getOptions()).extracting(PrototypeClarifyResponse.Option::getLabel)
                    .containsExactly("星耀夜空", "云海天光");
        });
    }

    @Test
    void shouldRestoreRequiredWallpaperAssetWhenFinalPlannerReturnsNone() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("用户最新确认答案"))).thenReturn("""
                {
                  "needsClarification": false,
                  "intentSummary": "小猫手机主题套装",
                  "generationBrief": "锁屏、主屏和组件页，每个页面都必须出现小猫。",
                  "assetPlan": {"required": false, "source": "NONE", "aspectRatio": "1:1"},
                  "questions": []
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeFinalizeRequest request = new PrototypeFinalizeRequest();
        request.setPlatform(Platform.APP);
        request.setDescription("做一个小猫主题手机壁纸，每页都有小猫");
        request.setGenerationBrief("小猫作为主题核心视觉");
        request.setAssetPlan(com.example.aidocumentplatform.model.dto.PrototypeAssetPlan.builder()
                .required(true)
                .source("GENERATED")
                .role("小猫主题角色")
                .prompt("毛茸茸的可爱小猫完整角色")
                .aspectRatio("3:4")
                .transparentBackground(true)
                .build());
        request.setClarificationAnswers(List.of("锁屏、主屏、组件页都出现小猫"));

        PrototypeClarifyResponse response = service.finalizePlan(request, 1L);

        assertThat(response.getAssetPlan().isRequired()).isTrue();
        assertThat(response.getAssetPlan().getRole()).isEqualTo("小猫主题角色");
    }

    @Test
    void shouldParseIndependentAssetsForMultiPageTheme() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("小猫主题"))).thenReturn("""
                {
                  "needsClarification": false,
                  "intentSummary": "小猫手机主题套装",
                  "generationBrief": "锁屏页的小猫抱手机，主屏页的小猫躲在图标后面。",
                  "assetPlan": {
                    "required": true, "source": "GENERATED", "role": "锁屏小猫",
                    "prompt": "小猫抱着手机", "aspectRatio": "9:16", "transparentBackground": false
                  },
                  "assetPlans": [
                    {
                      "key": "lock_screen", "targetPage": "锁屏页", "required": true,
                      "source": "GENERATED", "role": "锁屏小猫", "prompt": "小猫抱着手机",
                      "aspectRatio": "9:16", "transparentBackground": false
                    },
                    {
                      "key": "home_screen", "targetPage": "主屏页", "required": true,
                      "source": "GENERATED", "role": "主屏小猫", "prompt": "小猫躲在图标后面",
                      "aspectRatio": "9:16", "transparentBackground": false
                    }
                  ],
                  "questions": []
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.APP);
        request.setPrototypeType(PrototypeType.MULTI_PAGE);
        request.setDescription("做一套小猫主题，包含锁屏页和主屏页");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.getAssetPlans()).extracting(com.example.aidocumentplatform.model.dto.PrototypeAssetPlan::getKey)
                .containsExactly("lock_screen", "home_screen");
        assertThat(response.getAssetPlans()).extracting(com.example.aidocumentplatform.model.dto.PrototypeAssetPlan::getTargetPage)
                .containsExactly("锁屏页", "主屏页");
    }

    @Test
    void shouldBackfillAssetPlansWhenClarifyAiFails() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), anyString()))
                .thenThrow(new RuntimeException("Did not observe any item or terminal signal within 90000ms"));
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.APP);
        request.setPrototypeType(PrototypeType.MULTI_PAGE);
        request.setDescription("做一个真实的3D小猫主题的壁纸页面，包括锁屏页、充电动画页、主页");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.isNeedsClarification()).isFalse();
        assertThat(response.getAssetPlan().isRequired()).isTrue();
        assertThat(response.getAssetPlan().getSource()).isEqualTo("GENERATED");
        assertThat(response.getAssetPlans()).extracting(com.example.aidocumentplatform.model.dto.PrototypeAssetPlan::getTargetPage)
                .containsExactly("锁屏页", "充电页");
    }

    @Test
    void shouldNotBackfillWhenVisualKeywordsOnlyAppearInBriefProhibitions() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("人员管理"))).thenReturn("""
                {
                  "needsClarification": false,
                  "intentSummary": "公司人员管理后台",
                  "generationBrief": "WEB 多页；企业内部人员管理后台。应避免加入营销宣传页、人物主视觉、活动海报、夸张插画和无关数据。",
                  "assetPlan": {"required": false, "source": "NONE"},
                  "assetPlans": [],
                  "questions": []
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.WEB);
        request.setPrototypeType(PrototypeType.MULTI_PAGE);
        request.setDescription("公司人员管理的网站");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.getAssetPlan().isRequired()).isFalse();
        assertThat(response.getAssetPlans()).isEmpty();
    }

    @Test
    void shouldNotBackfillAssetPlansForPlainPagesWhenClarifyAiFails() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.WEB);
        request.setPrototypeType(PrototypeType.MULTI_PAGE);
        request.setDescription("做一个后台管理系统，包括用户列表页和订单详情页");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.getAssetPlan().isRequired()).isFalse();
        assertThat(response.getAssetPlans()).isEmpty();
    }

    @Test
    void shouldDeriveIndependentAssetsWhenMultiPagePlannerOnlyReturnsLegacyAssetPlan() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.generate(anyString(), contains("小猫主题"))).thenReturn("""
                {
                  "needsClarification": false,
                  "intentSummary": "小猫手机主题套装",
                  "generationBrief": "分别生成锁屏页、主屏页和组件页，每页采用适合对应场景的独立构图。",
                  "assetPlan": {
                    "required": true,
                    "source": "GENERATED",
                    "role": "小猫主题主视觉",
                    "prompt": "梦幻星空中的毛茸茸小猫主题视觉",
                    "aspectRatio": "9:16",
                    "transparentBackground": false
                  },
                  "questions": []
                }
                """);
        PrototypeClarifyServiceImpl service = new PrototypeClarifyServiceImpl(aiClient);
        PrototypeClarifyRequest request = new PrototypeClarifyRequest();
        request.setPlatform(Platform.APP);
        request.setPrototypeType(PrototypeType.MULTI_PAGE);
        request.setDescription("做一套小猫主题，包含锁屏页、主屏页和组件页");

        PrototypeClarifyResponse response = service.clarify(request, 1L);

        assertThat(response.getAssetPlans()).hasSize(3);
        assertThat(response.getAssetPlans()).extracting(com.example.aidocumentplatform.model.dto.PrototypeAssetPlan::getKey)
                .containsExactly("lock_screen", "home_screen", "widget_screen");
        assertThat(response.getAssetPlans()).extracting(com.example.aidocumentplatform.model.dto.PrototypeAssetPlan::getTargetPage)
                .containsExactly("锁屏页", "主屏页", "组件页");
        assertThat(response.getAssetPlans()).allSatisfy(plan -> {
            assertThat(plan.isRequired()).isTrue();
            assertThat(plan.getSource()).isEqualTo("GENERATED");
            assertThat(plan.getPrompt()).contains(plan.getTargetPage());
        });
        assertThat(response.getAssetPlan()).isSameAs(response.getAssetPlans().get(0));
    }
}
