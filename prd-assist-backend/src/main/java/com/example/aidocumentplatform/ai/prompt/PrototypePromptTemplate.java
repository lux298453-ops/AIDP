package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import org.springframework.stereotype.Component;

/**
 * 原型生成的 Prompt 模板。
 *
 * SINGLE_PAGE: AI 输出单文件 HTML（纯文本）
 * MULTI_PAGE:  AI 输出 JSON 页面数组 [{title, html}, ...]，含页面间跳转关系
 */
@Component
public class PrototypePromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是一位资深的前端开发工程师，擅长将产品需求转化为可交互的 HTML 原型页面。
            你的代码必须简洁、现代、功能完整，所有 CSS 和 JS 均内联在 HTML 中。
            """;

    /**
     * 根据原型类型分发到不同的 prompt 构建方法。
     */
    public String buildUserPrompt(String description, Platform platform, PrototypeType type) {
        return type == PrototypeType.MULTI_PAGE
                ? buildMultiPagePrompt(description, platform)
                : buildSinglePagePrompt(description, platform);
    }

    // ==================== 单页面 ====================

    private String buildSinglePagePrompt(String description, Platform platform) {
        return """
                请根据以下功能描述，生成一个完整的、可直接在浏览器中打开的 HTML 原型页面。

                %s

                【功能描述】
                %s

                【技术要求】
                1. 纯 HTML + CSS + JavaScript，单文件，内联所有样式和脚本
                2. 使用现代设计风格（圆角 8px、柔和阴影、间距舒适）
                3. 包含逼真的模拟数据
                4. 实现核心交互（按钮点击、Tab切换、列表滚动等）
                5. 主色调使用 #409EFF

                【重要】只输出 HTML 代码，首字符必须是 '<'。
                """.formatted(getStyleGuide(platform), description);
    }

    // ==================== 多页面 ====================

    private String buildMultiPagePrompt(String description, Platform platform) {
        String style = getStyleGuide(platform);
        return """
                请根据以下功能描述，生成一个多页面的交互原型（3~5 个页面）。

                %s

                【功能描述】
                %s

                【输出格式】
                你必须返回一个严格的 JSON 数组（不要包含 markdown 代码块标记），每个元素是一个页面对象：
                [
                  {
                    "title": "页面标题（如：登录页）",
                    "order": 1,
                    "html": "<!DOCTYPE html><html>...完整的可运行 HTML...</html>"
                  },
                  ...
                ]

                【页面设计要求】
                1. 每个页面对应一个完整的 HTML 文件（内联 CSS + JS）
                2. 页面之间通过按钮/链接跳转（用 JavaScript 模拟页面切换效果也可）
                3. 第一个页面(order=1)应作为首页或导航入口
                4. 建议包含的典型页面：
                   - 登录/注册页、首页/列表页、详情页、个人中心/设置页
                5. 使用现代设计风格，主色调 #409EFF
                6. 包含逼真的模拟数据

                【重要】
                请只输出 JSON 数组，不要包含 ```json 标记，不要添加任何解释文字。
                输出的第一个字符必须是 '['。
                """.formatted(style, description);
    }

    // ==================== 视觉风格指南 ====================

    private String getStyleGuide(Platform platform) {
        return switch (platform) {
            case APP -> """
                    【视觉风格】移动端设计(宽375px居中)，顶部导航栏 + 底部Tab栏，
                    圆角卡片、柔和阴影、干净留白、Element Plus 风格配色""";
            case WEB -> """
                    【视觉风格】桌面端设计(宽1200px居中)，顶部导航栏 + 左侧菜单 + 右侧内容区，
                    Element Plus 风格配色""";
            case MINI_PROGRAM -> """
                    【视觉风格】小程序风格(宽375px居中)，顶部标题栏 + 微信WeUI风格 + 底部操作栏""";
        };
    }

    public String getSystemPrompt() { return SYSTEM_PROMPT; }
}
