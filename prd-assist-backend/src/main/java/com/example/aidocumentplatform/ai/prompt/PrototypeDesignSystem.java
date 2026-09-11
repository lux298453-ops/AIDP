package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.PageMorphology;
import com.example.aidocumentplatform.model.enums.Platform;

import java.util.Map;

/**
 * 原型设计系统 —— 平台自研的「扁平组件包」+ 页面形态骨架模板。
 *
 * <p>设计目标（参考 v0 / bolt.new）：
 * AI 只负责「用 co-* 类拼装组件 + 填充内容」，样式全部由本类提供的固定 CSS 接管。
 * 所有组件都设计为「一层 div/button/input/table 结构」，不依赖任何 JS 组件库的
 * 内部 DOM 契约（Vant/Element 的 CSS 需要配套子结构，纯 CSS 注入对裸 HTML 不可靠，
 * 因此改为自研扁平组件包，保证 AI 输出任何结构都能完整生效）。</p>
 *
 * <p>单一样式来源：每张页面只注入一套 &lt;style data-proto-design-system&gt;，
 * 不再叠加质量守卫/平台守卫，避免 !important 互相覆盖导致排版失控。</p>
 */
public final class PrototypeDesignSystem {

    private PrototypeDesignSystem() {
    }

    /** 组件清单 —— AI 只能从这些 co-* 类中选择，每个类附 1:1 结构示例 */
    public static final String COMPONENT_LIST = """
            【组件清单 — 只允许使用以下 co-* 类；每个组件都是单层结构，按示例输出，禁止发明其他 class，禁止写 <style>】
            导航：
            - 顶部导航栏：<div class="co-navbar"><button class="co-nav-back">‹</button><div class="co-nav-title">标题</div><button class="co-nav-action">︙</button></div>
            - 底部标签栏（2~5 项，选中项加 active）：<div class="co-tabbar"><div class="co-tabbar-item active"><div class="co-tab-icon">◉</div><div class="co-tab-label">首页</div></div><div class="co-tabbar-item"><div class="co-tab-icon">◉</div><div class="co-tab-label">消息</div></div></div>
            - 侧边导航（仅 WEB/PAD 后台）：<aside class="co-sidebar"><div class="co-sidebar-brand">品牌名</div><nav class="co-sidebar-nav"><div class="co-nav-item active">仪表盘</div><div class="co-nav-item">订单</div></nav></aside>
            按钮：
            - <button class="co-btn co-btn-primary">确认</button>
            - 变体：co-btn-default 次要 / co-btn-danger 危险 / co-btn-text 文字 / co-btn-round 胶囊 / co-btn-block 整行 / co-btn-lg 大 / co-btn-sm 小 / co-btn-disabled 禁用
            输入：
            - 表单项：<div class="co-form-item"><label class="co-form-label">手机号</label><div class="co-input"><input placeholder="请输入"></div></div>
            - 搜索框：<div class="co-search"><span>⌕</span><input placeholder="搜索"></div>
            - 下拉选择（单选）：<select class="co-select"><option disabled selected>请选择</option><option>选项一</option><option>选项二</option></select>
            - 下拉选择（多选）：<select class="co-select" multiple><option>选项一</option><option>选项二</option></select>
            - 文件上传：<div class="co-upload"><button class="co-btn co-btn-default">选择文件</button><div class="co-upload-hint">支持 jpg/png/pdf，≤10MB</div><div class="co-upload-file"><span>需求文档.pdf</span><button>×</button></div></div>
            - 日历：<div class="co-calendar"><div class="co-calendar-head"><button class="co-calendar-btn">‹</button><div class="co-calendar-title">2026年8月</div><button class="co-calendar-btn">›</button></div><div class="co-calendar-week"><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span><span>日</span></div><div class="co-calendar-days"><span></span><span></span><button class="co-calendar-day">1</button><button class="co-calendar-day today">2</button><button class="co-calendar-day selected">8</button>…<button class="co-calendar-day outside">1</button></div></div>（月初空白用空 span 占位；今天加 today，选中日加 selected，跨月日期加 outside）
            展示：
            - 卡片：<div class="co-card"><div class="co-card-title">标题</div><div class="co-card-desc">描述</div></div>；内容少用 <div class="co-card compact">…</div>；标题+辅助行用 <div class="co-card-row"><div class="co-card-title">…</div><div class="co-card-meta">…</div></div>
            - 列表容器 + 列表项：<div class="co-list"><div class="co-cell"><div class="co-cell-media">张</div><div class="co-cell-body"><div class="co-cell-title">主标题</div><div class="co-cell-desc">副标题</div></div><div class="co-cell-extra">说明</div><div class="co-cell-arrow">›</div></div>…</div>
            - 标签：<span class="co-tag co-tag-primary">进行中</span>（变体 success/warning/danger）
            - 头像：<div class="co-avatar">张</div> 或 <div class="co-avatar"><img src="https://…"></div>；大头像加 co-avatar-lg
            - 宫格：<div class="co-grid"><div class="co-card">…</div>…</div>
            - 空状态：<div class="co-empty"><div class="co-empty-title">暂无数据</div><div class="co-empty-desc">描述</div></div>
            反馈：
            - 弹窗：<div class="co-dialog-mask"><div class="co-dialog"><button class="co-dialog-close">×</button><div class="co-dialog-title">标题</div><div class="co-dialog-body">内容</div><div class="co-dialog-actions"><button class="co-btn co-btn-default">取消</button><button class="co-btn co-btn-primary">确认</button></div></div></div>
            - 进度条：<div class="co-progress"><div class="co-progress-bar" style="width:60%"></div></div>
            表格（仅 WEB/PAD）：<div class="co-table-wrap"><table class="co-table"><tr><th>名称</th><th>状态</th><th>负责人</th><th>附件</th></tr><tr><td>订单A</td><td><span class="co-tag co-tag-success">已完成</span></td><td><select class="co-select"><option>张三</option><option>李四</option></select></td><td><div class="co-upload">…</div></td></tr></table></div>（单元格内允许：co-tag 状态、co-select 下拉、co-upload 上传、co-btn co-btn-sm 操作、co-progress 进度）
            指标卡（仅 WEB/PAD 看板）：<div class="co-metric-grid"><div class="co-metric"><div class="co-metric-value">128</div><div class="co-metric-label">订单数</div></div>…</div>
            布局：
            - 页面外壳：<div class="co-page-shell"><div class="co-page-content">…</div></div>
            - 桌面/平板壳（仅 WEB/PAD 完整页，侧栏必须在左、主区在右，禁止改顺序、禁止把侧栏放进 co-page-shell）：<div class="app-shell"><aside class="co-sidebar"><div class="co-sidebar-brand">品牌名</div><nav class="co-sidebar-nav"><div class="co-nav-item active">仪表盘</div></nav></aside><div class="main-wrap"><div class="co-header">…</div><main class="co-page-content">…</main></div></div>
            - 顶栏（WEB/PAD）：<div class="co-header"><div><div class="co-page-title">页面标题</div><div class="co-page-desc">描述</div></div><div class="co-toolbar"><button class="co-btn co-btn-primary">新建</button></div></div>
            - 底部操作栏：<div class="co-bottom-action"><button class="co-btn co-btn-primary">提交</button></div>
            - 步骤条：<div class="co-stepbar"><div class="co-step active"><div class="co-step-dot">1</div><span>第一步</span></div><div class="co-step"><div class="co-step-dot">2</div><span>第二步</span></div></div>
            使用规则：
            1. 所有 co-* 样式已由平台注入，只需按示例输出 HTML，禁止写 <style>
            2. 选择规则：列表→co-list/co-cell；卡片流→co-card；表单→co-form-item/co-input；确认弹窗→co-dialog；导航→co-navbar/co-tabbar；表格→co-table；看板→co-metric-grid
            3. 表格/表单中需要选择的列用 co-select（单选/多选），需要上传文件的列用 co-upload，日期相关用 co-calendar；禁止用 co-input 文本框冒充这些控件
            4. 禁止发明清单外的 class；禁止内联 style 修改颜色/尺寸（进度条宽度除外）
            5. 文字颜色已内建对比度规则，状态色通过 co-btn-*/co-tag-* 切换
            """;

    /** 页面形态骨架模板 —— 固定 HTML 结构，AI 只填内容 */
    private static final Map<PageMorphology, String> SHELLS = Map.of(
            PageMorphology.MODAL_POPUP, """
                    <div class="co-dialog-mask">
                      <div class="co-dialog">
                        <button class="co-dialog-close">×</button>
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
                    <div class="co-stage">
                      <div class="co-stage-box">
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
                      <div class="co-navbar">
                        <button class="co-nav-back">__NAV_LEFT__</button>
                        <div class="co-nav-title">__TITLE__</div>
                        <button class="co-nav-action">__NAV_RIGHT__</button>
                      </div>
                      <div class="co-page-content">
                        __CONTENT__
                      </div>
                      <div class="co-tabbar">
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
                    - __BODY__：弹窗主体内容，用 co-list/co-card/co-input/co-tag 等组件组织
                    - __ACTIONS__：1~2 个操作按钮（co-btn co-btn-primary 确认 + co-btn co-btn-default 取消）
                    - 禁止在弹窗外新增任何页面外壳、导航栏、标签栏
                    """,
            PageMorphology.COMPONENT_ONLY, """
                    填充规则：
                    - __COMPONENT__：只放一个组件（co-btn/co-card/co-input/co-dialog/co-cell/co-tag 等），居中展示
                    - 禁止多个组件堆叠，禁止新增页面外壳
                    """,
            PageMorphology.LIST_FEED, """
                    填充规则：
                    - __CONTENT__：用 co-list + co-cell/co-card 组织列表或卡片流，相邻项间距由平台样式保证
                    - 顶部可用 co-search 搜索栏
                    - 禁止底部标签栏和顶部导航栏
                    """,
            PageMorphology.FORM_FLOW, """
                    填充规则：
                    - __FORM__：用 co-form-item + co-input 组织表单（label + 输入框），表单单列
                    - __SUBMIT__：一个 co-btn co-btn-primary co-btn-block 提交按钮
                    """,
            PageMorphology.FULL_PAGE, """
                    填充规则：
                    - __TITLE__：页面标题
                    - __NAV_LEFT__：返回箭头 ‹（如需要）
                    - __NAV_RIGHT__：右侧操作（可选，最多 1 个）
                    - __CONTENT__：页面主体内容，用 co-card/co-list/co-cell/co-grid 等组织
                    - __TABBAR__：2~5 个 co-tabbar-item，每个含 co-tab-icon + co-tab-label，仅一个加 active
                    """,
            PageMorphology.AUTO, "");

    /**
     * 返回形态对应的骨架模板。
     *
     * @param morphology 页面形态
     * @return 骨架 HTML，占位符为 __XXX__；AUTO 形态返回空串（应已被后端解析为具体形态）
     */
    public static String shellFor(PageMorphology morphology) {
        if (morphology == null) return "";
        return SHELLS.getOrDefault(morphology, "");
    }

    /**
     * 返回形态对应的骨架模板（按平台）。
     * WEB/PAD 的完整页面使用「侧边栏 + 顶栏 + 主内容」桌面壳骨架。
     */
    public static String shellFor(PageMorphology morphology, Platform platform) {
        if (morphology == null) return "";
        if (morphology == PageMorphology.FULL_PAGE && (platform == Platform.WEB || platform == Platform.PAD)) {
            return """
                    <div class="app-shell">
                      <aside class="co-sidebar">
                        <div class="co-sidebar-brand">__TITLE__</div>
                        <nav class="co-sidebar-nav">
                          __SIDEBAR__
                        </nav>
                      </aside>
                      <div class="main-wrap">
                        <div class="co-header">
                          <div>
                            <div class="co-page-title">__TITLE__</div>
                            <div class="co-page-desc">__HEADER_DESC__</div>
                          </div>
                          <div class="co-toolbar">
                            __HEADER_ACTIONS__
                          </div>
                        </div>
                        <main class="co-page-content">
                          __CONTENT__
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

    /**
     * 返回形态对应的填充指令（按平台）。
     */
    public static String fillRuleFor(PageMorphology morphology, Platform platform) {
        if (morphology == null) return "";
        if (morphology == PageMorphology.FULL_PAGE && (platform == Platform.WEB || platform == Platform.PAD)) {
            return """
                    填充规则：
                    - __TITLE__：页面标题，同时用于侧边栏品牌区和顶栏标题区
                    - __SIDEBAR__：3~7 个 co-nav-item 导航项，其中一个加 active
                    - __HEADER_DESC__：一行页面摘要
                    - __HEADER_ACTIONS__：1~3 个右上角操作（co-btn）
                    - __CONTENT__：主工作区，用 co-card/co-table/co-metric-grid/co-form-item 等组织
                    - 桌面/平板完整页面必须保持 侧边栏 + 顶栏 + 主内容 布局
                    """;
        }
        return fillRuleFor(morphology);
    }

    /**
     * 按平台返回组件清单（当前为统一的 co-* 组件包）。
     */
    public static String componentListFor(Platform platform) {
        return COMPONENT_LIST;
    }

    /**
     * 生成完整设计系统注入块（按平台，差异为主题变量与画布规则）。
     * APP/小程序/PAD → OPPO ColorOS 主题（PAD 追加 1024px 平板画布）；WEB → Corporate Clean 主题。
     */
    public static String buildDesignSystemCss(Platform platform) {
        if (platform == Platform.WEB) {
            return buildDesktopDesignSystem();
        }
        return (platform == Platform.PAD)
                ? "<style data-proto-design-system>" + MOBILE_THEME + PAD_CANVAS_RULE + FLAT_KIT_CSS + NATURAL_HEIGHT_RULE + "</style>"
                : buildMobileDesignSystem();
    }

    /** 平板画布规则：PAD 页面 1024px 居中 */
    private static final String PAD_CANVAS_RULE = """
            body[data-proto-platform="PAD"] .co-page-shell,
            body[data-proto-platform="PAD"] .app-shell { width: min(1024px, 100vw); margin: 0 auto; }
            """;

    /**
     * 移动端设计系统注入块（OPPO ColorOS 主题）。
     * 注意：模板中含 % 字符，禁止使用 String.formatted()，必须用字符串拼接。
     */
    public static String buildMobileDesignSystem() {
        return "<style data-proto-design-system>" + MOBILE_THEME + FLAT_KIT_CSS + NATURAL_HEIGHT_RULE + "</style>";
    }

    /** Mobile and Pad previews grow with their real content instead of inheriting desktop viewport height. */
    private static final String NATURAL_HEIGHT_RULE = """
            body[data-proto-platform="APP"] .co-page-shell,
            body[data-proto-platform="APP"] .app-shell,
            body[data-proto-platform="APP"] .co-stage,
            body[data-proto-platform="MINI_PROGRAM"] .co-page-shell,
            body[data-proto-platform="MINI_PROGRAM"] .app-shell,
            body[data-proto-platform="MINI_PROGRAM"] .co-stage,
            body[data-proto-platform="PAD"] .co-page-shell,
            body[data-proto-platform="PAD"] .app-shell,
            body[data-proto-platform="PAD"] .co-stage { min-height: 0; }
            """;

    /**
     * 桌面端设计系统注入块（Corporate Clean 主题）。
     * 注意：模板中含 % 字符，禁止使用 String.formatted()，必须用字符串拼接。
     */
    public static String buildDesktopDesignSystem() {
        return "<style data-proto-design-system>" + DESKTOP_THEME + FLAT_KIT_CSS + "</style>";
    }

    /** OPPO ColorOS 主题变量（移动端） */
    private static final String MOBILE_THEME = """
            :root {
              --co-primary: #1BA784;
              --co-primary-dark: #148F6E;
              --co-primary-soft: rgba(27,167,132,.12);
              --co-success: #2ECC71;
              --co-success-soft: rgba(46,204,113,.14);
              --co-warning: #F1C40F;
              --co-warning-soft: rgba(241,196,15,.18);
              --co-danger: #E74C3C;
              --co-danger-soft: rgba(231,76,60,.12);
              --co-bg: #F7F8FA;
              --co-card: #FFFFFF;
              --co-card-soft: #F2F3F5;
              --co-text: #1A1A2E;
              --co-muted: #666666;
              --co-hint: #B0B0B0;
              --co-border: rgba(26,26,46,.08);
              --co-radius-card: 16px;
              --co-radius-btn: 20px;
              --co-radius-dialog: 24px;
              --co-radius-input: 12px;
              --co-shadow-card: 0 2px 8px rgba(0,0,0,.06);
              --co-shell-max: 390px;
              --co-pad: 16px;
              --co-gap: 12px;
              --co-grid-cols: 2;
            }
            """;

    /** Corporate Clean 主题变量（桌面端，PAD 另设画布宽度） */
    private static final String DESKTOP_THEME = """
            :root {
              --co-primary: #1e40af;
              --co-primary-dark: #18339c;
              --co-primary-soft: rgba(30,64,175,.10);
              --co-success: #16a34a;
              --co-success-soft: rgba(22,163,74,.12);
              --co-warning: #d97706;
              --co-warning-soft: rgba(217,119,6,.14);
              --co-danger: #dc2626;
              --co-danger-soft: rgba(220,38,38,.10);
              --co-bg: #f8fafc;
              --co-card: #ffffff;
              --co-card-soft: #f1f5f9;
              --co-text: #0f172a;
              --co-muted: #64748b;
              --co-hint: #94a3b8;
              --co-border: #e2e8f0;
              --co-radius-card: 12px;
              --co-radius-btn: 8px;
              --co-radius-dialog: 12px;
              --co-radius-input: 8px;
              --co-shadow-card: 0 1px 3px rgba(15,23,42,.08);
              --co-shell-max: 100%;
              --co-pad: 24px;
              --co-gap: 16px;
              --co-grid-cols: 3;
            }
            """;

    /**
     * 扁平组件包 CSS —— 唯一样式来源，所有组件均为单层结构。
     * 注意：模板中含 % 字符，禁止使用 String.formatted()，必须用字符串拼接。
     */
    private static final String FLAT_KIT_CSS = """
            * { box-sizing: border-box; }
            html, body { margin: 0; padding: 0; }
            body {
              background: var(--co-bg); color: var(--co-text);
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
              font-size: 15px; line-height: 1.55; overflow-x: hidden; min-width: 0;
            }
            img, svg, video { max-width: 100%; }
            button { font: inherit; cursor: pointer; }
            input, select, textarea { font: inherit; color: var(--co-text); }
            a { color: var(--co-primary); text-decoration: none; }
            h1, h2, h3, h4, p, ul, ol { margin: 0; }
            ul, ol { padding-left: 18px; }

            /* ===== 页面外壳 ===== */
            .co-page-shell {
              width: var(--co-shell-max); max-width: 100%; margin: 0 auto;
              min-height: 100vh; display: flex; flex-direction: column;
              background: var(--co-bg); overflow-x: hidden;
            }
            .co-page-content {
              flex: 1; min-width: 0; padding: var(--co-pad);
              display: flex; flex-direction: column; gap: var(--co-gap);
            }
            .app-shell { display: flex; min-height: 100vh; background: var(--co-bg); }
            .main-wrap { flex: 1; min-width: 0; display: flex; flex-direction: column; }

            /* ===== 顶部导航栏 ===== */
            .co-navbar {
              position: relative; display: flex; align-items: center; justify-content: center;
              min-height: 48px; padding: 0 16px; background: var(--co-card); flex-shrink: 0;
            }
            .co-nav-title {
              font-size: 17px; font-weight: 600; color: var(--co-text);
              text-align: center; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
              max-width: 60%;
            }
            .co-nav-back, .co-nav-action {
              position: absolute; top: 0; width: 48px; height: 48px;
              display: flex; align-items: center; justify-content: center;
              color: var(--co-text); font-size: 20px; line-height: 1;
              background: none; border: none; padding: 0;
            }
            .co-nav-back { left: 8px; }
            .co-nav-action { right: 8px; }

            /* ===== 底部标签栏 ===== */
            .co-tabbar {
              display: flex; flex-direction: row; flex-wrap: nowrap; align-items: stretch;
              height: 56px; background: var(--co-card); flex-shrink: 0;
              border-top: 1px solid var(--co-border); overflow: hidden;
            }
            .co-tabbar-item {
              flex: 1 1 0%; min-width: 0;
              display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px;
              color: var(--co-hint); cursor: pointer; background: none; border: none; padding: 4px 2px;
            }
            .co-tab-icon { font-size: 20px; line-height: 1; display: flex; align-items: center; justify-content: center; }
            .co-tab-label {
              font-size: 11px; line-height: 1.1;
              white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%;
            }
            .co-tabbar-item.active { color: var(--co-primary); }

            /* ===== 侧边栏（桌面） ===== */
            .co-sidebar {
              width: 240px; max-width: 240px; flex-shrink: 0;
              background: var(--co-card); border-right: 1px solid var(--co-border);
              display: flex; flex-direction: column; min-height: 100vh;
            }
            .co-sidebar-brand {
              min-height: 64px; display: flex; align-items: center;
              padding: 0 20px; font-size: 16px; font-weight: 700; color: var(--co-text);
              border-bottom: 1px solid var(--co-border); flex-shrink: 0;
            }
            .co-sidebar-nav { padding: 12px; display: flex; flex-direction: column; gap: 2px; flex: 1; }
            .co-nav-item {
              display: flex; align-items: center; gap: 10px; width: 100%;
              min-height: 40px; padding: 0 14px; margin: 2px 0;
              border-radius: 10px; border: none; background: none;
              color: var(--co-muted); font-size: 14px; text-align: left; cursor: pointer;
            }
            .co-nav-item:hover { background: var(--co-card-soft); }
            .co-nav-item.active { background: var(--co-primary-soft); color: var(--co-primary); font-weight: 600; }

            /* ===== 顶栏（桌面） ===== */
            .co-header {
              min-height: 64px; flex-shrink: 0; display: flex; align-items: center;
              justify-content: space-between; gap: 16px;
              padding: 0 24px; background: var(--co-card); border-bottom: 1px solid var(--co-border);
            }
            .co-page-title { font-size: 20px; font-weight: 600; color: var(--co-text); line-height: 1.3; }
            .co-page-desc { font-size: 13px; color: var(--co-muted); margin-top: 2px; line-height: 1.5; }
            .co-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

            /* ===== 按钮 ===== */
            .co-btn {
              display: inline-flex; align-items: center; justify-content: center; gap: 8px;
              min-height: 44px; padding: 0 20px;
              border-radius: var(--co-radius-btn); border: none;
              font-size: 15px; font-weight: 600; line-height: 1.2;
              white-space: nowrap; overflow: hidden; text-align: center;
              cursor: pointer; transition: all .15s ease-out; flex-shrink: 0;
              max-width: 100%;
            }
            .co-btn:active { transform: scale(.97); }
            .co-btn-primary { background: var(--co-primary); color: #fff; }
            .co-btn-primary:hover { background: var(--co-primary-dark); }
            .co-btn-default { background: var(--co-card-soft); color: var(--co-text); }
            .co-btn-danger { background: var(--co-danger); color: #fff; }
            .co-btn-text { background: transparent; color: var(--co-primary); }
            .co-btn-round { border-radius: 999px; }
            .co-btn-block { width: 100%; display: flex; }
            .co-btn-lg { min-height: 48px; font-size: 16px; }
            .co-btn-sm { min-height: 34px; padding: 0 14px; font-size: 13px; border-radius: 14px; }
            .co-btn-disabled { opacity: .4; pointer-events: none; }

            /* ===== 表单 ===== */
            .co-form-item { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; min-width: 0; }
            .co-form-label { font-size: 14px; font-weight: 500; color: var(--co-text); }
            .co-input {
              display: flex; align-items: center; gap: 8px;
              min-height: 44px; padding: 0 14px;
              background: var(--co-card-soft); border-radius: var(--co-radius-input); border: none;
            }
            .co-input input {
              flex: 1; min-width: 0; border: none; outline: none; background: transparent;
              font-size: 15px; color: var(--co-text);
            }
            .co-input input::placeholder { color: var(--co-hint); }
            .co-search {
              display: flex; align-items: center; gap: 8px;
              min-height: 40px; padding: 0 14px;
              background: var(--co-card-soft); border-radius: 999px; color: var(--co-hint);
            }
            .co-search input {
              flex: 1; min-width: 0; border: none; outline: none; background: transparent;
              font-size: 14px; color: var(--co-text);
            }

            /* ===== 下拉选择 ===== */
            .co-select {
              width: 100%; min-width: 0; min-height: 44px; padding: 0 34px 0 14px;
              background-color: var(--co-card-soft); border: none; border-radius: var(--co-radius-input);
              font-size: 15px; color: var(--co-text);
              appearance: none; -webkit-appearance: none;
              background-image:
                linear-gradient(45deg, transparent 50%, var(--co-muted) 50%),
                linear-gradient(135deg, var(--co-muted) 50%, transparent 50%);
              background-position: calc(100% - 18px) 50%, calc(100% - 13px) 50%;
              background-size: 5px 5px; background-repeat: no-repeat;
              cursor: pointer; line-height: 1.4;
            }
            .co-select:focus { outline: none; box-shadow: 0 0 0 2px var(--co-primary-soft); }
            .co-select option { color: var(--co-text); background: var(--co-card); }
            .co-select[multiple] {
              appearance: auto; -webkit-appearance: auto; background-image: none;
              min-height: 90px; padding: 8px; border-radius: var(--co-radius-input);
            }
            .co-table .co-select { min-width: 140px; }

            /* ===== 文件上传 ===== */
            .co-upload {
              display: flex; flex-direction: column; gap: 6px;
              padding: 10px; min-width: 0;
              border: 1px dashed var(--co-border); border-radius: var(--co-radius-input);
              background: var(--co-card-soft);
            }
            .co-upload .co-btn { align-self: flex-start; }
            .co-upload-hint { font-size: 11px; color: var(--co-hint); line-height: 1.4; }
            .co-upload-file {
              display: flex; align-items: center; justify-content: space-between; gap: 8px;
              min-height: 32px; padding: 4px 10px; border-radius: 8px;
              background: var(--co-card); font-size: 12px; color: var(--co-text);
              overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
            }
            .co-upload-file span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
            .co-upload-file button {
              background: none; border: none; color: var(--co-hint);
              font-size: 14px; line-height: 1; cursor: pointer; padding: 0 2px; flex-shrink: 0;
            }

            /* ===== 日历 ===== */
            .co-calendar {
              background: var(--co-card); border-radius: var(--co-radius-card);
              padding: 12px; box-shadow: var(--co-shadow-card); min-width: 0;
            }
            .co-calendar-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
            .co-calendar-title { font-size: 15px; font-weight: 600; color: var(--co-text); }
            .co-calendar-btn {
              width: 34px; height: 34px; border-radius: 10px; border: none;
              background: var(--co-card-soft); color: var(--co-text);
              font-size: 16px; line-height: 1; display: flex; align-items: center; justify-content: center;
              cursor: pointer; padding: 0; flex-shrink: 0;
            }
            .co-calendar-week, .co-calendar-days { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
            .co-calendar-week span {
              text-align: center; font-size: 12px; color: var(--co-hint);
              padding: 4px 0; line-height: 1.4;
            }
            .co-calendar-day {
              height: 36px; border-radius: 10px; border: none; background: none;
              font-size: 13px; color: var(--co-text); text-align: center; line-height: 1;
              display: flex; align-items: center; justify-content: center;
              cursor: pointer; padding: 0; min-width: 0;
            }
            .co-calendar-day.outside { color: var(--co-hint); }
            .co-calendar-day.today { border: 1px solid var(--co-primary); color: var(--co-primary); background: var(--co-primary-soft); }
            .co-calendar-day.selected { background: var(--co-primary); color: #fff; font-weight: 600; }

            /* ===== 卡片 ===== */
            .co-card {
              background: var(--co-card); border-radius: var(--co-radius-card);
              padding: 16px; box-shadow: var(--co-shadow-card);
              display: flex; flex-direction: column; gap: 8px; min-width: 0;
            }
            .co-card.compact { padding: 10px 12px; gap: 4px; }
            .co-card-title { font-size: 16px; font-weight: 600; color: var(--co-text); line-height: 1.4; overflow-wrap: break-word; }
            .co-card-desc { font-size: 13px; color: var(--co-muted); line-height: 1.5; overflow-wrap: break-word; }
            .co-card-meta { font-size: 12px; color: var(--co-hint); flex-shrink: 0; }
            .co-card-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-width: 0; }
            .co-card-row .co-card-title { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

            /* ===== 列表 ===== */
            .co-list { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
            .co-cell {
              display: flex; align-items: center; gap: 12px;
              min-height: 52px; padding: 12px 16px;
              background: var(--co-card); border-radius: var(--co-radius-card);
              box-shadow: var(--co-shadow-card); min-width: 0;
            }
            .co-cell-media {
              width: 40px; height: 40px; border-radius: 12px; background: var(--co-card-soft);
              display: flex; align-items: center; justify-content: center;
              color: var(--co-muted); font-size: 16px; font-weight: 600;
              flex-shrink: 0; overflow: hidden;
            }
            .co-cell-media img { width: 100%; height: 100%; object-fit: cover; }
            .co-cell-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
            .co-cell-title {
              font-size: 15px; font-weight: 500; color: var(--co-text);
              white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
            }
            .co-cell-desc {
              font-size: 12px; color: var(--co-muted);
              white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
            }
            .co-cell-extra { font-size: 13px; color: var(--co-muted); flex-shrink: 0; }
            .co-cell-arrow { color: var(--co-hint); font-size: 16px; flex-shrink: 0; }

            /* ===== 标签 ===== */
            .co-tag {
              display: inline-flex; align-items: center; justify-content: center;
              min-height: 22px; padding: 0 9px; border-radius: 999px;
              font-size: 11px; font-weight: 500; line-height: 1;
              white-space: nowrap; flex-shrink: 0;
            }
            .co-tag-primary { background: var(--co-primary-soft); color: var(--co-primary); }
            .co-tag-success { background: var(--co-success-soft); color: var(--co-success); }
            .co-tag-warning { background: var(--co-warning-soft); color: var(--co-warning); }
            .co-tag-danger { background: var(--co-danger-soft); color: var(--co-danger); }

            /* ===== 宫格 ===== */
            .co-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }

            /* ===== 弹窗 ===== */
            .co-dialog-mask {
              position: fixed; inset: 0; z-index: 2000;
              display: flex; align-items: center; justify-content: center;
              background: rgba(0,0,0,.40); padding: 24px;
            }
            .co-dialog {
              position: relative; width: min(320px, 100%);
              background: var(--co-card); border-radius: var(--co-radius-dialog);
              padding: 24px 20px 18px; box-shadow: 0 8px 24px rgba(0,0,0,.12);
              display: flex; flex-direction: column; gap: 12px;
              max-height: 85vh; overflow-y: auto;
            }
            .co-dialog-title { font-size: 17px; font-weight: 600; color: var(--co-text); text-align: center; line-height: 1.4; }
            .co-dialog-body { font-size: 14px; color: var(--co-muted); line-height: 1.6; overflow-wrap: break-word; }
            .co-dialog-actions { display: flex; gap: 12px; justify-content: center; margin-top: 4px; }
            .co-dialog-actions .co-btn { flex: 1; }
            .co-dialog-close {
              position: absolute; top: 10px; right: 10px;
              width: 36px; height: 36px; display: flex; align-items: center; justify-content: center;
              font-size: 20px; line-height: 1; color: var(--co-hint);
              background: none; border: none; border-radius: 50%; padding: 0;
            }

            /* ===== 组件特写舞台 ===== */
            .co-stage { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px; background: var(--co-bg); }
            .co-stage-box { width: 100%; max-width: 420px; display: flex; flex-direction: column; gap: 12px; align-items: center; }

            /* ===== 空状态 ===== */
            .co-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 48px 24px; gap: 8px; text-align: center; }
            .co-empty-title { font-size: 15px; font-weight: 500; color: var(--co-text); }
            .co-empty-desc { font-size: 13px; color: var(--co-hint); }

            /* ===== 头像 ===== */
            .co-avatar {
              width: 40px; height: 40px; border-radius: 50%;
              background: var(--co-card-soft); color: var(--co-muted);
              display: inline-flex; align-items: center; justify-content: center;
              overflow: hidden; flex-shrink: 0; font-weight: 600; font-size: 14px; text-align: center;
            }
            .co-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .co-avatar-lg { width: 56px; height: 56px; font-size: 20px; }

            /* ===== 进度条 ===== */
            .co-progress { height: 4px; border-radius: 2px; background: var(--co-card-soft); overflow: hidden; }
            .co-progress-bar { height: 100%; background: var(--co-primary); border-radius: 2px; }

            /* ===== 底部操作栏 ===== */
            .co-bottom-action {
              position: sticky; bottom: 0; z-index: 100;
              padding: 12px 16px; background: var(--co-card);
              box-shadow: 0 -2px 8px rgba(0,0,0,.06);
              display: flex; gap: 12px;
            }
            .co-bottom-action .co-btn { flex: 1; }

            /* ===== 步骤条 ===== */
            .co-stepbar { display: flex; align-items: flex-start; gap: 8px; padding: 4px 0; }
            .co-step { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 11px; color: var(--co-hint); text-align: center; min-width: 0; }
            .co-step-dot {
              width: 22px; height: 22px; border-radius: 50%;
              background: var(--co-card-soft); color: var(--co-muted);
              display: flex; align-items: center; justify-content: center;
              font-size: 11px; font-weight: 600; flex-shrink: 0;
            }
            .co-step.active { color: var(--co-primary); }
            .co-step.active .co-step-dot { background: var(--co-primary); color: #fff; }
            .co-step.done .co-step-dot { background: var(--co-primary-soft); color: var(--co-primary); }

            /* ===== 表格（桌面） ===== */
            .co-table-wrap {
              background: var(--co-card); border-radius: var(--co-radius-card);
              box-shadow: var(--co-shadow-card); overflow: hidden; min-width: 0;
              overflow-x: auto;
            }
            .co-table { width: 100%; border-collapse: collapse; font-size: 14px; }
            .co-table th {
              background: var(--co-card-soft); color: var(--co-muted);
              font-weight: 600; font-size: 13px; text-align: left;
              padding: 12px 16px; border-bottom: 1px solid var(--co-border);
              white-space: nowrap;
            }
            .co-table td { padding: 12px 16px; border-bottom: 1px solid var(--co-border); color: var(--co-text); vertical-align: middle; }
            .co-table tr:last-child td { border-bottom: none; }
            .co-table td .co-btn { min-height: 34px; padding: 0 12px; font-size: 13px; border-radius: 12px; white-space: normal; word-break: break-all; }
            .co-table td .co-select, .co-table td .co-upload { max-width: 220px; }

            /* ===== 指标卡（桌面） ===== */
            .co-metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 14px; }
            .co-metric {
              background: var(--co-card); border-radius: var(--co-radius-card);
              padding: 16px; box-shadow: var(--co-shadow-card);
              display: flex; flex-direction: column; gap: 4px; min-width: 0;
            }
            .co-metric-value { font-size: 24px; font-weight: 700; color: var(--co-text); line-height: 1.2; }
            .co-metric-label { font-size: 13px; color: var(--co-muted); }

            /* ===== 响应式 ===== */
            @media (max-width: 768px) {
              .co-metric-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }
              .co-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }
              .co-sidebar { width: 200px; max-width: 200px; min-height: 100vh; }
              .co-header { padding: 0 16px; min-height: 56px; }
              .co-page-content { padding: 16px; }
            }
            """;
}
