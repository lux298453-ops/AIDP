package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.PageMorphology;
import com.example.aidocumentplatform.model.enums.Platform;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 原型设计系统 —— 固定 CSS + 页面形态骨架模板。
 *
 * <p>设计目标（参考 v0 / bolt.new）：
 * AI 只负责「拼装组件 + 填充内容」，样式全部由本类提供的固定 CSS 接管。
 * 预览页通过 iframe srcdoc 内联渲染，因此设计系统 CSS 必须内联注入 HTML
 * 才能保证预览准确。</p>
 *
 * <p>CSS 来源（按平台拆分）：</p>
 * <ul>
 *   <li>APP / 小程序 / PAD → Vant 4 组件库（static/vant/index.css），CSS 变量覆盖为 OPPO ColorOS 风格</li>
 *   <li>WEB → Element Plus 组件库（static/element/index.css），CSS 变量覆盖为 Corporate Clean 风格</li>
 * </ul>
 */
public final class PrototypeDesignSystem {

    private PrototypeDesignSystem() {
    }

    /** Vant 4 完整组件 CSS（缓存，只读一次） */
    private static volatile String vantCss;

    /** Element Plus 完整组件 CSS（缓存，只读一次） */
    private static volatile String elementCss;

    /** 组件清单 —— AI 只能从这些 class 中选择，禁止发明新 class（移动端：APP / 小程序 / PAD） */
    public static final String COMPONENT_LIST_MOBILE = """
            【组件清单 — 只能从以下组件中选择，禁止发明清单外的 class】
            Vant 组件（class 前缀 van-，样式已由平台注入）：
            - 导航：.van-nav-bar（顶部导航栏）、.van-tabbar（底部标签栏）、.van-tabbar-item（标签项）、.van-tabs（标签页）、.van-tab（标签）、.van-steps（步骤条）、.van-step
            - 按钮：.van-button、.van-button--primary、.van-button--default、.van-button--danger、.van-button--warning、.van-button--success、.van-button--round、.van-button--block、.van-button--large、.van-button--small、.van-button--mini、.van-button--plain、.van-button--disabled
            - 输入：.van-field（输入框）、.van-cell-group、.van-search（搜索框）、.van-switch（开关）、.van-checkbox、.van-radio、.van-stepper（步进器）、.van-slider（滑块）、.van-picker（选择器）
            - 展示：.van-card（商品卡片）、.van-cell（列表项）、.van-cell-group、.van-tag（标签）、.van-badge（徽标）、.van-image（图片）、.van-icon（图标）、.van-empty（空状态）、.van-skeleton（骨架屏）、.van-collapse（折叠面板）
            - 反馈：.van-dialog（弹窗）、.van-toast（提示）、.van-overlay（遮罩）、.van-popup（弹出层）、.van-action-sheet（动作面板）、.van-loading（加载）、.van-progress（进度条）、.van-count-down（倒计时）、.van-notice-bar（通知栏）
            - 布局：.van-grid（宫格）、.van-grid-item、.van-row、.van-col、.van-swipe（轮播）、.van-swipe-item
            OPPO ColorOS 自定义类（样式已由平台注入）：
            - .co-modal-mask（弹窗遮罩）、.co-dialog（弹窗卡片）、.co-dialog-title、.co-dialog-body、.co-dialog-actions、.co-dialog-close
            - .co-component-stage（组件特写舞台）、.co-component-box、.co-page-shell（手机页面外壳）、.co-page-content（页面内容区）
            - .co-bottom-action（底部操作栏）
            使用规则：
            1. 组件样式已由平台注入，你只需写 &lt;div class="van-button van-button--primary"&gt;确认&lt;/div&gt; 这样的引用
            2. 选择规则：有列表→.van-cell/.van-card；有表单→.van-field；有确认→.van-dialog；有切换→.van-tabbar
            3. 禁止写任何 &lt;style&gt; 块，禁止发明清单外的 class，禁止自定义颜色/尺寸
            4. 如需自定义颜色，只能通过平台提供的 class（如 .van-button--primary）切换，禁止内联 style
            """;

    /** 组件清单 —— AI 只能从这些 class 中选择，禁止发明新 class（桌面端：WEB） */
    public static final String COMPONENT_LIST_WEB = """
            【组件清单 — 只能从以下组件中选择，禁止发明清单外的 class】
            Element Plus 组件（class 前缀 el-，样式已由平台注入）：
            - 布局：.el-container、.el-header、.el-aside、.el-main、.el-footer、.el-row、.el-col
            - 导航：.el-menu、.el-menu-item、.el-sub-menu、.el-breadcrumb、.el-breadcrumb-item、.el-tabs、.el-tab-pane、.el-steps、.el-step
            - 按钮：.el-button、.el-button--primary、.el-button--default、.el-button--success、.el-button--warning、.el-button--danger、.el-button--text、.el-button--large、.el-button--small、.el-button--round
            - 输入：.el-form、.el-form-item、.el-input、.el-input__inner、.el-select、.el-date-picker、.el-switch、.el-checkbox、.el-radio、.el-slider、.el-input-number、.el-textarea
            - 展示：.el-card、.el-table、.el-table-column、.el-tag、.el-badge、.el-avatar、.el-image、.el-icon、.el-empty、.el-skeleton、.el-progress、.el-descriptions、.el-descriptions-item、.el-statistic
            - 反馈：.el-dialog、.el-message、.el-message-box、.el-notification、.el-loading、.el-popover、.el-tooltip、.el-dropdown、.el-dropdown-item、.el-alert、.el-result
            - 容器：.el-collapse、.el-collapse-item、.el-drawer、.el-page-header
            自定义类（样式已由平台注入）：
            - .co-modal-mask（弹窗遮罩）、.co-dialog（弹窗卡片）、.co-dialog-title、.co-dialog-body、.co-dialog-actions、.co-dialog-close
            - .co-component-stage（组件特写舞台）、.co-component-box、.co-page-shell（页面外壳）、.co-page-content（页面内容区）
            - .co-bottom-action（底部操作栏）
            使用规则：
            1. 组件样式已由平台注入，你只需写 &lt;el-button class="el-button el-button--primary"&gt;确认&lt;/el-button&gt; 这样的引用
            2. 选择规则：有表格→.el-table；有表单→.el-form/.el-input；有确认→.el-dialog；有导航→.el-menu
            3. 禁止写任何 &lt;style&gt; 块，禁止发明清单外的 class，禁止自定义颜色/尺寸
            4. 如需自定义颜色，只能通过平台提供的 class（如 .el-button--primary）切换，禁止内联 style
            """;

    /** 页面形态骨架模板 —— 固定 HTML 结构，AI 只填内容 */
    private static final Map<PageMorphology, String> SHELLS = Map.of(
            PageMorphology.MODAL_POPUP, """
                    <div class="co-modal-mask">
                      <div class="co-dialog">
                        <div class="co-dialog-close">×</div>
                        <div class="co-dialog-title">__TITLE__</div>
                        <div class="co-dialog-body">
                          __BODY__
                        </div>
                        <div class="co-dialog-actions">
                          __ACTIONS__
                        </div>
                      </div>
                    </div>
                    """,
            PageMorphology.COMPONENT_ONLY, """
                    <div class="co-component-stage">
                      <div class="co-component-box">
                        __COMPONENT__
                      </div>
                    </div>
                    """,
            PageMorphology.LIST_FEED, """
                    <div class="co-page-shell">
                      <div class="co-page-content">
                        __CONTENT__
                      </div>
                    </div>
                    """,
            PageMorphology.FORM_FLOW, """
                    <div class="co-page-shell">
                      <div class="co-page-content">
                        __FORM__
                      </div>
                      <div class="co-bottom-action">
                        __SUBMIT__
                      </div>
                    </div>
                    """,
            PageMorphology.FULL_PAGE, """
                    <div class="co-page-shell">
                      <div class="van-nav-bar" title="__TITLE__">
                        __NAV_LEFT__<template #right>__NAV_RIGHT__</template>
                      </div>
                      <div class="co-page-content">
                        __CONTENT__
                      </div>
                      <div class="van-tabbar" style="position:relative">
                        __TABBAR__
                      </div>
                    </div>
                    """,
            PageMorphology.AUTO, "");

    /** 形态 → 填充指令（告诉 AI 每个占位符填什么） */
    private static final Map<PageMorphology, String> FILL_RULES = Map.of(
            PageMorphology.MODAL_POPUP, """
                    填充规则：
                    - __TITLE__：弹窗标题文字
                    - __BODY__：弹窗主体内容，用 .van-button/.van-tag/.van-image 等组件组织
                    - __ACTIONS__：1~2 个操作按钮（.van-button--primary 确认 + .van-button--default 取消）
                    - 禁止在弹窗外新增任何页面外壳、导航栏、标签栏
                    """,
            PageMorphology.COMPONENT_ONLY, """
                    填充规则：
                    - __COMPONENT__：只放一个组件（按钮/卡片/输入框/弹窗/列表项等），居中展示
                    - 禁止多个组件堆叠，禁止新增页面外壳
                    """,
            PageMorphology.LIST_FEED, """
                    填充规则：
                    - __CONTENT__：用 .van-cell / .van-card / .van-cell-group 组织列表或卡片流
                    - 顶部可用 .van-search 搜索栏
                    - 禁止底部标签栏和顶部导航栏（除非内容确实需要）
                    """,
            PageMorphology.FORM_FLOW, """
                    填充规则：
                    - __FORM__：用 .van-cell-group + .van-field 组织表单（label + 输入框）
                    - __SUBMIT__：一个 .van-button--primary .van-button--round .van-button--block 提交按钮
                    - 表单单列布局，字段间距由 .van-cell-group 控制
                    """,
            PageMorphology.FULL_PAGE, """
                    填充规则：
                    - __TITLE__：页面标题
                    - __NAV_LEFT__：返回按钮（可选）
                    - __NAV_RIGHT__：右侧操作图标（可选，最多1个）
                    - __CONTENT__：页面主体内容，用 .van-cell/.van-card/.van-field 等组件组织
                    - __TABBAR__：2~5 个 .van-tabbar-item，每个含 .van-icon + 文字
                    """,
            PageMorphology.AUTO, "");

    /**
     * 返回形态对应的骨架模板。
     *
     * @param morphology 页面形态
     * @return 骨架 HTML，占位符为 __XXX__；AUTO 形态返回空串（AI 自行判断）
     */
    public static String shellFor(PageMorphology morphology) {
        if (morphology == null) return "";
        return SHELLS.getOrDefault(morphology, "");
    }

    public static String shellFor(PageMorphology morphology, Platform platform) {
        if (morphology == null) return "";
        if (morphology == PageMorphology.FULL_PAGE && (platform == Platform.WEB || platform == Platform.PAD)) {
            return """
                    <div class="app-shell">
                      <aside class="sidebar">
                        <div class="sidebar-header">__TITLE__</div>
                        <div class="nav-list">
                          __SIDEBAR__
                        </div>
                      </aside>
                      <div class="main-wrap">
                        <div class="app-header">
                          <div class="header-title">
                            <div class="page-title">__TITLE__</div>
                            <div class="page-description">__HEADER_DESC__</div>
                          </div>
                          <div class="header-actions">
                            __HEADER_ACTIONS__
                          </div>
                        </div>
                        <main class="page-main">
                          <div class="page-content">
                            __CONTENT__
                          </div>
                        </main>
                      </div>
                    </div>
                    """;
        }
        return shellFor(morphology);
    }

    /**
     * 返回形态对应的填充指令。
     */
    public static String fillRuleFor(PageMorphology morphology) {
        if (morphology == null) return "";
        return FILL_RULES.getOrDefault(morphology, "");
    }

    public static String fillRuleFor(PageMorphology morphology, Platform platform) {
        if (morphology == null) return "";
        if (morphology == PageMorphology.FULL_PAGE && (platform == Platform.WEB || platform == Platform.PAD)) {
            return """
                    Fill rules:
                    - __TITLE__: page title, reused in the sidebar brand area and header title area
                    - __SIDEBAR__: 3 to 7 navigation items using .el-menu-item or .nav-item; do not generate a bottom tab bar
                    - __HEADER_DESC__: one-line page summary or state description
                    - __HEADER_ACTIONS__: 1 to 3 top-right actions, prefer .el-button or .btn
                    - __CONTENT__: the main work area, organized with desktop/tablet form, table, card, and detail components
                    - Desktop and tablet full pages must keep the sidebar + header + main-content layout; never fall back to a mobile top bar plus bottom navigation shell
                    """;
        }
        return fillRuleFor(morphology);
    }

    /**
     * 读取并缓存 Vant 4 完整组件 CSS（static/vant/index.css）。
     */
    public static String vantCss() {
        if (vantCss != null) return vantCss;
        synchronized (PrototypeDesignSystem.class) {
            if (vantCss != null) return vantCss;
            vantCss = readResource("static/vant/index.css");
        }
        return vantCss;
    }

    /**
     * 读取并缓存 Element Plus 完整组件 CSS（static/element/index.css）。
     */
    public static String elementCss() {
        if (elementCss != null) return elementCss;
        synchronized (PrototypeDesignSystem.class) {
            if (elementCss != null) return elementCss;
            elementCss = readResource("static/element/index.css");
        }
        return elementCss;
    }

    private static String readResource(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 按平台返回组件清单。
     * APP / 小程序 / PAD → Vant 移动端组件；WEB → Element Plus 桌面端组件。
     */
    public static String componentListFor(Platform platform) {
        return (platform == Platform.WEB || platform == Platform.PAD) ? COMPONENT_LIST_WEB : COMPONENT_LIST_MOBILE;
    }

    /**
     * 生成完整设计系统注入块（按平台）。
     * APP / 小程序 / PAD → Vant + OPPO ColorOS 变量；WEB → Element Plus + Corporate Clean 变量。
     */
    public static String buildDesignSystemCss(Platform platform) {
        return (platform == Platform.WEB || platform == Platform.PAD) ? buildElementDesignSystem() : buildVantDesignSystem();
    }

    /**
     * 生成 Vant + OPPO ColorOS 设计系统注入块（移动端）。
     * 注意：模板中含 % 字符，禁止使用 String.formatted()，必须用字符串拼接。
     */
    public static String buildVantDesignSystem() {
        return """
                <style data-proto-design-system>
                /* ===== OPPO ColorOS 主题变量（覆盖 Vant 默认） ===== */
                :root {
                  --van-primary-color: #1BA784;
                  --van-primary-color-dark: #148F6E;
                  --van-success-color: #2ECC71;
                  --van-danger-color: #E74C3C;
                  --van-warning-color: #F1C40F;
                  --van-gray-1: #F7F8FA;
                  --van-gray-2: #F2F3F5;
                  --van-gray-6: #666666;
                  --van-gray-7: #1A1A2E;
                  --van-gray-8: #1A1A2E;
                  --van-radius-md: 12px;
                  --van-radius-lg: 16px;
                  --van-radius-max: 20px;
                  --van-button-round-border-radius: 999px;
                  --van-button-border-radius: 20px;
                  --van-nav-bar-height: 48px;
                  --van-tabbar-height: 56px;
                  --van-field-label-width: 96px;
                  --co-primary: #1BA784;
                  --co-primary-dark: #148F6E;
                  --co-bg: #F7F8FA;
                  --co-card: #FFFFFF;
                  --co-text: #1A1A2E;
                  --co-muted: #666666;
                  --co-hint: #B0B0B0;
                  --co-radius-btn: 20px;
                  --co-radius-card: 16px;
                  --co-radius-dialog: 24px;
                }
                body { background: var(--co-bg); color: var(--co-text); }
                """ + vantCss() + """
                /* ===== OPPO ColorOS 自定义组件 ===== */
                .co-modal-mask {
                  position: fixed; inset: 0; z-index: 2000;
                  display: flex; align-items: center; justify-content: center;
                  background: rgba(0,0,0,0.40); padding: 24px;
                }
                .co-dialog {
                  position: relative; width: min(320px, 100%);
                  background: var(--co-card); border-radius: var(--co-radius-dialog);
                  padding: 28px 24px 20px;
                  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
                  display: flex; flex-direction: column; gap: 14px;
                  max-height: 85vh; overflow-y: auto;
                }
                .co-dialog-title {
                  font-size: 18px; font-weight: 600; color: var(--co-text);
                  text-align: center; line-height: 1.4;
                }
                .co-dialog-body { font-size: 15px; color: var(--co-muted); line-height: 1.5; }
                .co-dialog-actions { display: flex; gap: 12px; justify-content: center; margin-top: 6px; }
                .co-dialog-actions .van-button { flex: 1; }
                .co-dialog-close {
                  position: absolute; top: 12px; right: 12px;
                  width: 44px; height: 44px; display: flex; align-items: center; justify-content: center;
                  font-size: 22px; color: var(--co-hint); cursor: pointer;
                  border-radius: 50%;
                }
                .co-dialog-close:hover { background: rgba(0,0,0,0.05); }
                .co-component-stage {
                  min-height: 100vh; display: flex; align-items: center; justify-content: center;
                  background: var(--co-bg); padding: 24px;
                }
                .co-component-box { width: 100%; max-width: 420px; display: flex; flex-direction: column; gap: 12px; align-items: center; }
                .co-page-shell {
                  width: min(390px, 100vw); margin: 0 auto;
                  min-height: 100vh; display: flex; flex-direction: column;
                  background: var(--co-bg); overflow-x: hidden;
                }
                .co-page-content { flex: 1; padding: 16px; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
                .co-bottom-action {
                  position: sticky; bottom: 0; z-index: 100;
                  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
                  background: var(--co-card);
                  box-shadow: 0 -2px 8px rgba(0,0,0,0.06);
                }
                .co-bottom-action .van-button { width: 100%; }
                .van-nav-bar { background: var(--co-card); border-bottom: none; }
                .van-tabbar { background: var(--co-card); border-top: none; }
                .van-tabbar-item--active { color: var(--co-primary); }
                .van-button--primary { background: var(--co-primary); border-color: var(--co-primary); }
                .van-cell { background: var(--co-card); }
                .van-cell-group { border-radius: var(--co-radius-card); overflow: hidden; margin-bottom: 12px; }
                .van-card { background: var(--co-card); border-radius: var(--co-radius-card); overflow: hidden; }
                .van-field { background: var(--co-card); }
                .van-dialog { border-radius: var(--co-radius-dialog); }
                .van-popup--bottom { border-radius: var(--co-radius-dialog) var(--co-radius-dialog) 0 0; }
                .van-image__img { border-radius: 8px; }
                .van-tag { border-radius: 16px; }
                .van-search { background: transparent; }
                .van-search__content { border-radius: 20px; background: #EFEFF0; }
                .van-empty { padding: 48px 24px; }
                </style>
                """;
    }

    /**
     * 生成 Element Plus + Corporate Clean 设计系统注入块（WEB 桌面端）。
     * 注意：模板中含 % 字符，禁止使用 String.formatted()，必须用字符串拼接。
     */
    public static String buildElementDesignSystem() {
        return """
                <style data-proto-design-system>
                /* ===== Corporate Clean 主题变量（覆盖 Element Plus 默认） ===== */
                :root {
                  --el-color-primary: #1e40af;
                  --el-color-primary-light-3: #4d6cd0;
                  --el-color-primary-light-5: #7e95dd;
                  --el-color-primary-light-7: #aebeea;
                  --el-color-primary-light-8: #c7d2f0;
                  --el-color-primary-light-9: #e7ecf8;
                  --el-color-primary-dark-2: #18339c;
                  --el-color-success: #16a34a;
                  --el-color-warning: #d97706;
                  --el-color-danger: #dc2626;
                  --el-color-error: #dc2626;
                  --el-color-info: #64748b;
                  --el-border-radius-base: 8px;
                  --el-border-radius-small: 6px;
                  --el-border-radius-round: 999px;
                  --el-font-size-base: 14px;
                  --co-primary: #1e40af;
                  --co-bg: #f8fafc;
                  --co-card: #ffffff;
                  --co-text: #0f172a;
                  --co-muted: #64748b;
                  --co-hint: #94a3b8;
                  --co-radius-card: 12px;
                  --co-radius-dialog: 12px;
                }
                body {
                  margin: 0; background: var(--co-bg); color: var(--co-text);
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
                  line-height: 1.55;
                }
                * { box-sizing: border-box; }
                """ + elementCss() + """
                /* ===== Corporate Clean 自定义组件 ===== */
                .co-modal-mask {
                  position: fixed; inset: 0; z-index: 2000;
                  display: flex; align-items: center; justify-content: center;
                  background: rgba(0,0,0,0.40); padding: 24px;
                }
                .co-dialog {
                  position: relative; width: min(440px, 100%);
                  background: var(--co-card); border-radius: var(--co-radius-dialog);
                  padding: 24px; box-shadow: 0 8px 24px rgba(0,0,0,0.12);
                  display: flex; flex-direction: column; gap: 14px;
                  max-height: 85vh; overflow-y: auto;
                }
                .co-dialog-title { font-size: 18px; font-weight: 600; color: var(--co-text); text-align: center; }
                .co-dialog-body { font-size: 15px; color: var(--co-muted); line-height: 1.6; }
                .co-dialog-actions { display: flex; gap: 12px; justify-content: flex-end; margin-top: 6px; }
                .co-dialog-close {
                  position: absolute; top: 12px; right: 12px;
                  width: 36px; height: 36px; display: flex; align-items: center; justify-content: center;
                  font-size: 20px; color: var(--co-hint); cursor: pointer; border-radius: 8px;
                }
                .co-dialog-close:hover { background: rgba(0,0,0,0.05); }
                .co-component-stage {
                  min-height: 100vh; display: flex; align-items: center; justify-content: center;
                  background: var(--co-bg); padding: 24px;
                }
                .co-component-box { width: 100%; max-width: 640px; display: flex; flex-direction: column; gap: 12px; align-items: center; }
                .co-page-shell { min-height: 100vh; display: flex; flex-direction: column; background: var(--co-bg); }
                .co-page-content { flex: 1; padding: 24px; min-width: 0; }
                .co-bottom-action {
                  position: sticky; bottom: 0; z-index: 100;
                  padding: 16px 24px; background: var(--co-card);
                  box-shadow: 0 -2px 8px rgba(0,0,0,0.06);
                  display: flex; justify-content: flex-end; gap: 12px;
                }
                .el-card { border-radius: var(--co-radius-card); }
                .el-button--primary { --el-button-bg-color: var(--co-primary); --el-button-border-color: var(--co-primary); }
                .el-table { --el-table-header-bg-color: #f8fafc; }
                .el-dialog { border-radius: var(--co-radius-dialog); }
                .el-tag { border-radius: 6px; }
                .el-menu { border-right: none; }
                </style>
                """;
    }
}
