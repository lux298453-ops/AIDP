package com.example.aidocumentplatform.model.enums;

import java.util.Locale;

/**
 * 页面形态 —— 控制 AI 生成原型的结构骨架。
 *
 * <p>解决"想生成弹窗却被生成为完整页面"的问题：
 * 用户先明确选择页面形态，后端按形态注入固定骨架模板和组件约束，
 * AI 只负责往骨架里填充组件和内容，不再自行决定页面结构。</p>
 */
public enum PageMorphology {

    /** 完整页面：含页面外壳、顶部导航栏、底部标签栏 */
    FULL_PAGE,

    /** 弹窗/浮层：只生成弹窗组件，居中展示在半透明遮罩上，无页面外壳 */
    MODAL_POPUP,

    /** 列表/信息流：以列表项/卡片流为主，无固定导航栏 */
    LIST_FEED,

    /** 表单/流程：以表单输入为主，单列布局，底部提交操作 */
    FORM_FLOW,

    /** 组件特写：只生成单个组件，居中展示，用于组件库/细节展示 */
    COMPONENT_ONLY,

    /** 自由模式：不注入骨架约束，AI 根据描述自行判断页面结构 */
    AUTO;

    /**
     * 描述 → 形态的关键词判定（用于 AUTO 模式）。
     * 生成前把描述翻译成具体形态并注入对应骨架，避免 AI 裸奔。
     */
    public static PageMorphology autoFromDescription(String description) {
        if (description == null || description.isBlank()) return FULL_PAGE;
        String d = description.toLowerCase(Locale.ROOT);
        if (containsAny(d, "弹窗", "弹层", "浮层", "浮窗", "提示框", "确认框", "对话框", "modal", "dialog", "popup", "pop-up", "toast")) {
            return MODAL_POPUP;
        }
        if (containsAny(d, "列表", "信息流", "通知", "消息", "记录", "动态", "账单", "订单列表", "feed", "timeline", "message")) {
            return LIST_FEED;
        }
        if (containsAny(d, "表单", "注册", "登录", "填写", "提交", "报名", "申请", "创建任务", "新建", "form", "signup", "register", "login")) {
            return FORM_FLOW;
        }
        if (containsAny(d, "组件", "卡片", "按钮", "输入框", "标签", "特写", "控件", "component", "widget")) {
            return COMPONENT_ONLY;
        }
        return FULL_PAGE;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
