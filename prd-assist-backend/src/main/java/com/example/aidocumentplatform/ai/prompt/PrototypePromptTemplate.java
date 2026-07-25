package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import org.springframework.stereotype.Component;

/**
 * 原型生成的 Prompt 模板。
 *
 * SINGLE_PAGE: AI 输出单文件 HTML（纯文本）
 * MULTI_PAGE:  AI 输出 JSON 页面数组 [{title, html}, ...]，含页面间跳转关系
 *
 * 针对 Grok / 中转站等模型，强制要求「完整内联 CSS」，禁止只写 class 不写样式。
 */
@Component
public class PrototypePromptTemplate {

    private static final String SYSTEM_PROMPT = """
            你是一位资深的前端开发工程师，擅长将产品需求转化为可交互、视觉完整的 HTML 原型页面。

            硬性规则（违反会导致页面“没样式/乱版”）：
            1. 必须输出完整可运行的 HTML 文档：以 <!DOCTYPE html> 开头，包含 <html><head><body>。
            2. 所有视觉样式必须写在 <head> 内的 <style>...</style> 中，使用真实 CSS 选择器（如 .header, .btn, .card）。
            3. 禁止只写 Tailwind/Bootstrap 类名而不引入对应 CSS。默认不要使用 Tailwind/Bootstrap。
            4. 禁止依赖外部 CSS 文件（cdn 链接也不要用，除非同时给出完整内联兜底样式）。
            5. JavaScript 必须内联在 <script> 中，不要引用外部 JS。
            6. 不要输出 markdown 代码块（不要 ```），不要输出解释文字，不要输出思考过程。
            7. 页面必须有清晰布局：背景色、字号、间距、圆角、阴影、主色按钮、卡片层次都要有。
            """;

    /**
     * 根据原型类型分发到不同的 prompt 构建方法。
     *
     * @param hasReferenceImage 是否附带风格参考图（影响风格说明段）
     */
    public String buildUserPrompt(String description, Platform platform, PrototypeType type) {
        return buildUserPrompt(description, platform, type, false, null);
    }

    public String buildUserPrompt(String description, Platform platform, PrototypeType type,
                                  boolean hasReferenceImage, String referenceFileName) {
        return type == PrototypeType.MULTI_PAGE
                ? buildMultiPagePrompt(description, platform, hasReferenceImage, referenceFileName)
                : buildSinglePagePrompt(description, platform, hasReferenceImage, referenceFileName);
    }

    private String buildReferenceStyleHint(boolean hasReferenceImage, String fileName) {
        if (!hasReferenceImage) return "";
        String name = (fileName == null || fileName.isBlank()) ? "用户上传的参考图" : fileName;
        return """

                【风格参考图】
                用户上传了一张风格参考图（文件名：%s）。
                - 若你能看到图片：请提取其主色、圆角、间距、字体气质、组件风格，并在 <style> 中实现同等视觉效果。
                - 若你无法查看图片：仍按现代、干净、留白充足的产品设计风格生成，主色 #409EFF。
                - 不要在 HTML 中嵌入该图片本身，只借鉴其视觉风格。
                - 保持交互完整与可读性，优先可用性。
                """.formatted(name);
    }

    private String buildCssRequirements() {
        return """
                【CSS 强制要求 — 必须全部满足】
                1. 在 <head> 中写完整 <style> 块，至少覆盖：
                   - * 或 body 的 box-sizing、font-family、background、color、line-height
                   - 页面容器布局（flex/grid）、header/nav/sidebar/main/footer
                   - 按钮 .btn / .btn-primary（背景色、padding、圆角、hover）
                   - 卡片 .card（白底、圆角、阴影、padding）
                   - 表单 input/select/textarea（边框、圆角、padding、focus）
                   - 列表/表格间距与分割线
                2. 使用语义化 class 名（.header, .nav-item, .content, .card, .btn），
                   并在 <style> 里为这些 class 写对应规则。禁止“只有 class 没有 CSS”。
                3. 主色 #409EFF，背景 #f5f7fa，文字 #303133，边框 #e4e7ed，圆角 8px。
                4. 不要输出未闭合的 HTML；</style></head><body>...</body></html> 必须齐全。
                5. 若内容较长，优先保证样式完整，再写核心页面结构与模拟数据。
                """;
    }

    // ==================== 单页面 ====================

    private String buildSinglePagePrompt(String description, Platform platform,
                                         boolean hasReferenceImage, String referenceFileName) {
        return """
                请根据以下功能描述，生成一个完整的、可直接在浏览器中打开的 HTML 原型页面。

                %s
                %s
                %s
                【功能描述】
                %s

                【技术要求】
                1. 纯 HTML + CSS + JavaScript，单文件
                2. 所有 CSS 写在 <style> 内，所有 JS 写在 <script> 内
                3. 使用现代设计风格（圆角 8px、柔和阴影、间距舒适、层次清晰）
                4. 包含逼真的模拟数据
                5. 实现核心交互（按钮点击、Tab 切换、列表滚动、简单表单反馈等）
                6. 主色调 #409EFF（若有参考图则优先跟随参考图主色）

                【输出格式】
                - 只输出 HTML 代码
                - 第一个非空白字符必须是 '<'
                - 推荐以 <!DOCTYPE html> 开头
                - 禁止 markdown、禁止前言、禁止结尾说明
                """.formatted(
                getStyleGuide(platform),
                buildCssRequirements(),
                buildReferenceStyleHint(hasReferenceImage, referenceFileName),
                description);
    }

    // ==================== 多页面 ====================

    private String buildMultiPagePrompt(String description, Platform platform,
                                        boolean hasReferenceImage, String referenceFileName) {
        String style = getStyleGuide(platform);
        return """
                请根据以下功能描述，生成一个多页面的交互原型（3~5 个页面）。

                %s
                %s
                %s
                【功能描述】
                %s

                【输出格式】
                你必须返回一个严格的 JSON 数组（不要包含 markdown 代码块标记），每个元素是一个页面对象：
                [
                  {
                    "title": "页面标题（如：登录页）",
                    "order": 1,
                    "html": "<!DOCTYPE html><html><head><meta charset=\\"UTF-8\\"><style>...完整CSS...</style></head><body>...完整页面...</body></html>"
                  }
                ]

                【页面设计要求】
                1. 每个页面的 html 都必须是完整可运行文档，且自带完整 <style>
                2. 页面之间必须可跳转：给跳转按钮/链接写 data-proto-page="目标order"
                   例如：从登录页去首页 → <button data-proto-page="2">进入首页</button>
                   也可使用 href="#page-2" 或 href="#首页"
                3. 第一个页面(order=1)作为首页或导航入口
                4. 建议包含：登录/注册、首页/列表、详情、个人中心/设置
                5. 所有页面视觉体系统一（配色、圆角、字号、按钮样式一致）
                6. 包含逼真模拟数据
                7. 每个页面是独立 HTML，不要把多个页面的 DOM 塞进同一个 html 字段

                【重要】
                请只输出 JSON 数组，不要包含 ```json 标记，不要添加任何解释文字。
                输出的第一个字符必须是 '['。
                每个页面的 html 字段内禁止只写 Tailwind 类名而不写 CSS。
                """.formatted(style, buildCssRequirements(),
                buildReferenceStyleHint(hasReferenceImage, referenceFileName), description);
    }

    // ==================== AI 辅助修改（局部编辑） ====================

    private static final String EDIT_SYSTEM_PROMPT = """
            你是一位资深前端工程师，负责根据产品经理的自然语言指令，对现有 HTML 原型做「最小必要」的修改。
            规则：
            1. 只改动指令涉及的部分，其余 HTML/CSS/JS 一律原样保留，不得重排或删除无关内容。
            2. 保持单文件结构，所有样式与脚本继续内联；若新增元素，必须同步补充 <style> 中的 CSS。
            3. 输出必须是可直接运行的完整 HTML（含 <!DOCTYPE html> 与完整 <style>）。
            4. 不要输出 markdown 代码块，不要输出解释文字。
            """;

    /** AI 编辑用的系统提示词 */
    public String getEditSystemPrompt() {
        return EDIT_SYSTEM_PROMPT;
    }

    /**
     * 构建「按自然语言指令修改现有 HTML」的用户提示词。
     * 约定 AI 用分隔符输出「修改说明 + 新 HTML」，避免把整份 HTML 塞进 JSON 转义导致解析失败。
     *
     * @param currentHtml   当前原型 HTML（基准）
     * @param instruction   用户的自然语言修改描述
     * @param targetElement 可选，指定要修改的元素；为空传 null/空串
     */
    public String buildEditPrompt(String currentHtml, String instruction, String targetElement) {
        String scope = (targetElement == null || targetElement.isBlank())
                ? "（未指定具体元素，请根据描述自行定位）"
                : "重点修改这个元素：" + targetElement;

        return """
                这是当前的 HTML 原型代码：
                ------- 当前 HTML 开始 -------
                %s
                ------- 当前 HTML 结束 -------

                【修改需求】
                %s

                【修改范围】
                %s

                【输出格式】严格按下面两段输出，不要包含 markdown 代码块（不要 ``` ）：
                @@SUMMARY@@
                用一句中文说明你做了哪些改动
                @@HTML@@
                修改后的完整 HTML（第一个字符必须是 '<'，必须保留/补全完整 <style>）
                """.formatted(currentHtml, instruction, scope);
    }

    // ==================== 视觉风格指南 ====================

    private String getStyleGuide(Platform platform) {
        return switch (platform) {
            case APP -> """
                    【视觉风格】移动端设计(宽375px居中)，顶部导航栏 + 底部Tab栏，
                    圆角卡片、柔和阴影、干净留白、Element Plus 风格配色。
                    请在 CSS 中用固定宽度容器实现手机框效果。""";
            case WEB -> """
                    【视觉风格】桌面端设计(宽100%或最大1200px居中)，顶部导航栏 + 左侧菜单 + 右侧内容区，
                    Element Plus 风格配色。布局用 flex，侧边栏固定宽度。""";
            case PAD -> """
                    【视觉风格】平板端设计(宽834px居中)，支持横竖屏布局，采用侧边导航与双栏内容区，
                    保证触控热区不小于44px并适配较大字号。""";
            case MINI_PROGRAM -> """
                    【视觉风格】小程序风格(宽375px居中)，顶部标题栏 + 微信WeUI风格 + 底部操作栏。
                    请在 CSS 中实现完整小程序页面层次。""";
        };
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }
}
