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
            你熟练掌握 StyleKit 的 Corporate Clean（企业简洁风）设计系统，输出的原型必须一次成型、结构清晰、排版稳定、无重叠无堆叠。

            硬性规则（违反会导致页面“没样式/乱版/重叠”）：
            1. 必须输出完整可运行的 HTML 文档：以 <!DOCTYPE html> 开头，包含 <html><head><body>。
            2. 所有视觉样式必须写在 <head> 内的 <style>...</style> 中，使用真实 CSS 选择器（如 .header, .btn, .card）。
               在写任何组件样式之前，必须先写出全局 reset、box-sizing、overflow 保护和排版保护规则（详见下方 CSS 强制要求）。
            3. 禁止只写 Tailwind/Bootstrap 类名而不引入对应 CSS。默认不要使用 Tailwind/Bootstrap。
            4. 禁止依赖外部 CSS 文件（cdn 链接也不要用，除非同时给出完整内联兜底样式）。
            5. JavaScript 必须内联在 <script> 中，不要引用外部 JS。
            6. 不要输出 markdown 代码块（不要 ```），不要输出解释文字，不要输出思考过程。
            7. 页面必须有清晰布局：背景色、字号、间距、圆角、阴影、主色按钮、卡片层次都要有。
            8. 避免堆砌装饰性小图标、emoji、SVG 图形和图标网格；图标只能用于导航、按钮、状态等明确功能点。
               每个页面最多使用 4 个功能性图标，不能用大量小图标代替信息层级和布局设计。
            9. 一次性输出要求：
               - 不要等前端反馈再补样式，必须在第一次响应中就给出可直接查看的完整原型。
               - 所有容器必须有明确宽度/高度或 flex 自适应规则，禁止出现“无尺寸盒子导致内容溢出或塌陷”。
               - 所有文字必须包裹在有 padding 的容器内，禁止文字直接贴边或与其他文字重叠。
               - 所有并列元素之间必须有 gap 或 margin，禁止出现元素紧贴甚至重叠。
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
                - 若你能看到图片：请提取其主色、辅助色、背景质感、圆角、阴影、边框、按钮形状、卡片质感、字体气质和列表密度，并在 <style> 中实现同等视觉效果。
                - 参考图默认只覆盖视觉风格，不强行覆盖页面布局；只有当用户描述明确要求“按参考图布局/复刻布局/照着图片结构”时，才尽量复用参考图结构。
                - 若你无法查看图片：仍按现代、干净、留白充足的产品设计风格生成，主色 #409EFF。
                - 不要在 HTML 中嵌入该图片本身，只借鉴其视觉风格。
                - 保持交互完整与可读性，优先可用性。
                - 不要因为有参考图就生成大量图标；参考图主要用于视觉风格，不是让你复刻装饰元素数量。
                """.formatted(name);
    }

    private String buildCssRequirements() {
        return """
                【CSS 强制要求 — 必须全部满足】
                1. <style> 必须首先出现的全局基础骨架（写在任何组件样式之前）：
                   ```css
                   *, *::before, *::after { box-sizing: border-box; }
                   html, body { margin: 0; padding: 0; }
                   body {
                     font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
                     line-height: 1.55;
                     color: #1e293b;
                     background: #f8fafc;
                   }
                   img, svg, video, canvas { display: block; max-width: 100%; }
                   button, input, select, textarea { font: inherit; }
                   /* 排版保护：防止 flex/grid 子项收缩到 0 导致重叠 */
                   .app-shell, .page-shell, .workspace-shell { min-width: 0; }
                   .page-main, .page-content, .panel, .card, .table-panel, .form-panel,
                   .detail-panel, .summary-panel, .metric-card, .mobile-shell, .phone-shell {
                     min-width: 0;
                     overflow-wrap: break-word;
                   }
                   ```
                2. 使用语义化 class 名（.header, .nav-item, .content, .card, .btn），
                   并在 <style> 里为这些 class 写对应规则。禁止“只有 class 没有 CSS”。
                3. 默认采用 StyleKit Corporate Clean 设计 token：
                   - 主色 #1e40af（ corporate-blue ），次主色 #3b82f6，按钮 hover #1d4ed8。
                   - Web 背景 #f8fafc，卡片 #ffffff，边框 #e2e8f0，强边框 #cbd5e1。
                   - 正文 #0f172a，辅助文字 #64748b，占位 #94a3b8。
                   - App/小程序端主色 #0bb6c7 可覆盖默认主色；背景 #FAFAFA，卡片 #FFFFFF，正文 rgba(0,0,0,.90)，辅助文字 rgba(0,0,0,.60)。
                4. 不要输出未闭合的 HTML；</style></head><body>...</body></html> 必须齐全。
                5. 若内容较长，优先保证样式完整，再写核心页面结构与模拟数据。
                6. 使用专业产品界面表达，不要依赖装饰图标制造“设计感”：
                   - 禁止整页散落大量小图标、emoji、彩色符号、无意义 SVG 插画。
                   - 若需要图标，只能少量用于菜单、按钮、状态提示，并保持尺寸统一、颜色克制。
                   - 视觉重点应来自布局、对齐、留白、字体层级、颜色和组件状态。
                7. 【移动端高度规则】App/小程序端页面高度必须由内容自然决定，禁止用 min-height:100vh 强行撑满屏幕；
                   禁止为了“填满屏幕”而故意加大 padding、margin、行高或卡片间距；
                   内容少时页面就紧凑，底部直接结束，不要留大片空白。
                8. 【重叠/溢出防御 — 强制】
                   - 任何 flex 子元素如果会受兄弟挤压，必须设置 min-width:0 或 min-height:0。
                   - 文本容器必须设置 overflow-wrap: break-word; word-wrap: break-word;（兼容旧浏览器）。
                   - 固定侧边栏必须设置 flex-shrink:0; 主内容区设置 flex:1; min-width:0;。
                   - 表格必须放在 overflow-x:auto 的 .table-scroll 容器内，禁止宽表格撑破页面。
                   - 按钮文字禁止溢出：按钮必须设置 white-space:nowrap; overflow:hidden; text-overflow:ellipsis; 或足够 padding 保证不贴边。
                   - 卡片内部如果包含标题+说明+操作，必须使用 flex column + gap，禁止用绝对定位或 float 导致重叠。
                9. 【响应式断点】
                   - 桌面端默认 ≥992px 使用完整布局；平板 768~991px 可退化为双栏或收缩侧边栏；移动端 ≤767px 必须单列。
                   - 在媒体查询中，侧边栏宽度不得超过 100%，移动端应隐藏或变为抽屉/底部导航。
                   - 移动端卡片 padding 统一 14~16px，按钮宽度 100%（主按钮可单独设置）。
                """;
    }

    private String buildGenerationStrategy(Platform platform, boolean hasReferenceImage) {
        String styleSource = hasReferenceImage
                ? "已上传参考图：使用参考图提取到的颜色、圆角、阴影、背景质感、按钮和卡片风格覆盖默认 Corporate Clean 视觉风格。"
                : "未上传参考图：使用内置默认风格 Corporate Clean（企业简洁风），保持专业、干净、克制、可读。";
        return """
                【智能生成策略】
                1. 前端已明确选择终端平台：%s。必须以该平台为准，禁止再从【功能描述】反推 App/Web/Pad。
                2. 【功能描述】只用于判断页面类型和业务内容，例如登录页、列表页、详情页、看板页、表单流程页、设置页。
                3. 如果描述主要是业务需求，没有明确布局设计：在已选平台内先判断页面类型，再套用对应布局规范。
                4. 如果描述已经明确布局/颜色/风格/尺寸/组件位置：尊重用户明确要求，只用模板规则补齐缺失的可用性和排版规范。
                5. 优先级：前端选择的平台 > 用户明确布局/尺寸要求 > 页面类型布局规范 > 用户明确视觉风格/参考图视觉风格 > 默认 Corporate Clean SaaS 风格。
                6. %s

                【页面类型判断与布局规范】
                - 登录注册/找回密码/验证码/邀请码：使用当前平台下的认证页结构；禁止生成后台侧边栏和无关功能介绍。
                - 后台管理/配置平台/CRUD/文档管理/PRD审查：使用当前平台下的信息管理布局。Web 可用侧栏，App 禁止侧栏，Pad 可用主从双栏。
                - 内容详情/文章/公告/帮助/PRD详情：使用当前平台下的阅读布局。Web/Pad 控制正文宽度，App 使用单列内容流。
                - 数据看板/统计分析/监控：使用当前平台下的看板布局。Web/Pad 可网格化，App 使用纵向卡片列表。
                - 表单流程/审批/导入/创建任务：使用当前平台下的步骤和表单布局。Web/Pad 可双栏，App 必须单列并保证触控热区。

                【默认设计系统：StyleKit Corporate Clean SaaS 派生规范】
                - 视觉气质：B2B SaaS / 企业后台 / 文档工具，清爽、专业、轻质感、信息密度适中。
                - 色彩：主色 #1e40af（corporate-blue），次主色 #3b82f6，按钮 hover #1d4ed8，背景 #f8fafc，面板 #ffffff，边框 #e2e8f0，强边框 #cbd5e1，正文 #0f172a，次级文字 #64748b。
                - 字号：页面标题 22~24px，区块标题 16~18px，正文 14px，辅助文字 12~13px；禁止小于 12px。
                - 尺寸：按钮高度 40px，小按钮 34px，输入框高度 38~40px，表格行高 48px，导航项高度 42px，触控目标最小 44x44px。
                - 间距：使用 8/12/14/20/26/30px 节奏；桌面主内容 padding 26px 30px，移动端 padding 16px。
                - 圆角：控件 8px，卡片/面板 10~12px（rounded-lg / rounded-xl），品牌标识 9px；不要到处使用超大胶囊圆角。
                - 阴影：面板使用 0 1px 3px rgba(15,23,42,.08) 到 0 4px 12px rgba(15,23,42,.05) 的轻阴影；卡片 hover 时阴影微升；控件使用 0 1px 2px rgba(15,23,42,.05)。
                - 图标：每页最多 4 个功能性图标；禁止 emoji 和装饰性图标堆叠。
                - 高度规则：桌面端页面可占满视口；移动端页面禁止强制 min-height:100vh，高度由内容自然决定，内容少则紧凑结束。
                """.formatted(platform.name(), styleSource);
    }

    private String buildComponentSpecification() {
        return """
                【内置组件与排版规范 — 用户未明确指定时必须采用】
                这些规范用于固定按钮样式、侧边栏排版、主页面排版、文字大小和边距，避免 AI 自由发挥导致拥挤、混乱或过度装饰。

                【语义 class 合同 — 必须使用】
                为了让后端质量兜底 CSS 能稳定接管布局和组件规格，生成 HTML 时必须优先使用这些语义 class：
                - 外壳：app-shell / page-shell / workspace-shell
                - 侧栏：sidebar / sidebar-header / nav-list / nav-item
                - 顶栏：app-header / header-title / header-actions
                - 主区：page-main / page-content / page-title / page-description / toolbar / action-bar
                - 面板：panel / card / table-panel / form-panel / detail-panel / summary-panel / metric-card
                - 步骤：steps / step-list / step-item / phase-item / stage-item / process-item
                - 表单：form-grid / form-group / form-item / field-group / input-group / form-actions
                - 表格：data-table / table-scroll / action-cell / table-actions
                - 按钮：btn / btn-primary / btn-secondary / btn-text
                - 状态：status-tag / badge / tag
                - 登录：auth-shell / auth-card / auth-visual / login-card / register-card
                - 状态页：empty-state / error-state / loading-state / success-banner
                - 移动端：mobile-shell / phone-shell / mobile-header / mobile-content / mobile-tabbar / bottom-tab
                禁止只写随机 class 名；如果需要业务语义 class，也要同时保留上述规范 class。

                A. 全局布局与防塌/防重叠
                   A1. 桌面页面默认使用 100vw / min-height:100vh，不要生成窄小孤立画布。
                   A2. 移动端页面高度由内容自然决定，禁止用 min-height:100vh 强制撑满屏幕；内容少时底部直接结束，不留大片空白。
                   A3. 后台/工作台布局标准骨架：
                       .app-shell { display:flex; min-height:100vh; }
                       .sidebar { width:248px; flex-shrink:0; }
                       .main-wrap { flex:1; min-width:0; display:flex; flex-direction:column; }
                       .app-header { height:72px; flex-shrink:0; padding:0 30px; }
                       .page-main { flex:1; min-width:0; padding:26px 30px 40px; overflow-x:hidden; }
                   A4. 左侧栏禁止使用 30vw、33%、40% 这类比例宽度；桌面端不得超过 260px，除非用户明确要求超宽目录。
                   A5. 主内容区 padding 26px 30px 40px；区块之间 gap 14~20px；内容最大宽度根据页面类型控制，不要所有内容挤在一列。
                   A6. 登录注册页不使用后台侧边栏；默认居中卡片宽 380~440px，或左右分栏 48%/52%。
                   A7. 内容详情页正文宽 720~960px，行高 1.7，右侧目录宽 220~280px。
                   A8. 数据看板可使用 4 列指标卡；主工作区可使用 1.55fr / 0.85fr 双栏；窄屏自动单列。

                B. 内容安全边距（重叠防线）
                   B1. 任何有边框、背景色、圆角或阴影的容器，内部文字必须距离外边框至少 14px（移动端卡片 14~16px，不要过大）。
                   B2. Web 端卡片/面板/表单分组/步骤块默认 padding 20px；移动端卡片/步骤块 padding 14~16px；指标卡 padding 16px；小提示块 padding 12px 14px。
                   B3. 移动端步骤卡内部要紧凑：数字/图标与标题间距 8~10px，标题与说明间距 4~6px，卡片整体高度由内容决定，禁止为了“填满屏幕”而加大 padding 或高度。
                   B4. 横向步骤条每个步骤块必须使用 display:flex 且保留内部 padding，不允许标题贴着左上角。
                   B5. 侧边栏品牌区左右 padding 20px；导航列表整体 padding 22px 12px；导航项内部 padding 11px 12px。
                   B6. 标题不能贴着容器左上角：容器内第一个标题与容器边框至少 14px，标题下方说明 margin-top 4~6px（移动端）/ 6~8px（桌面端）。
                   B7. 表单 label 与输入框之间 gap 8px；连续表单项之间 gap 16px（移动端）~ 18px（桌面端）。
                   B8. 禁止出现文字与外层 div 边框距离小于 8px 的情况，除非是表格内分割线和极小状态标签。

                C. 页面类型规范
                   C1. 登录页：auth-shell 居中或左右分栏；auth-card 宽 420px、padding 32px；auth-visual padding 48px；只保留必要登录内容。
                   C2. 工作台/后台首页：app-shell + sidebar + app-header + page-main；指标区用 metric-card 四列；内容区 gap 20px。
                   C3. CRUD 表格页：page-title 区 + toolbar/filter-bar + table-panel + table-scroll + data-table + 分页；表格操作列使用 action-cell/table-actions。
                   C4. 表单流程页：steps/step-list 在顶部，step-item padding 14px 16px；下方 form-panel 两列表单，右侧 summary-panel。
                   C5. 内容详情页：detail-panel 阅读宽 720~960px，padding 24px；目录/摘要面板 280~320px。
                   C6. 数据看板页：metric-card + chart panel + table-panel；图表面板 padding 20px，图表周围留白至少 18px。
                   C7. 设置页：form-panel 分组布局，每个 setting-card padding 20px，开关/按钮右对齐。
                   C8. 弹窗/抽屉：modal-card padding 24px，footer 操作区 gap 12px；空/错/成功状态使用 center-state padding 30px。

                D. StyleKit Corporate Clean 组件食谱（必须按此写 CSS）
                   以下给出的数值是默认值，参考图/用户规范只能覆盖颜色、圆角、阴影、字体气质，不要覆盖尺寸和内部间距。

                   D1. 按钮 .btn
                       ```css
                       .btn {
                         display: inline-flex; align-items: center; justify-content: center;
                         height: 40px; min-width: 88px; padding: 0 16px;
                         border-radius: 8px; font-weight: 600; font-size: 14px;
                         white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                         cursor: pointer; border: 1px solid transparent;
                         transition: all 150ms ease-out;
                       }
                       .btn-primary { background: #1e40af; color: #fff; border-color: #1e40af; }
                       .btn-primary:hover { background: #1d4ed8; transform: translateY(-1px); box-shadow: 0 2px 6px rgba(30,64,175,.15); }
                       .btn-primary:active { transform: scale(0.98) translateY(0); }
                       .btn-secondary { background: #fff; color: #334155; border-color: #e2e8f0; }
                       .btn-secondary:hover { background: #f8fafc; }
                       .btn-text { background: transparent; color: #1e40af; border-color: transparent; }
                       ```
                       规则：按钮必须有 hover/active/focus；focus 使用 2px ring + 2px offset；同一组按钮高度一致。

                   D2. 卡片 .card / .panel
                       ```css
                       .card {
                         background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
                         padding: 20px; box-shadow: 0 1px 3px rgba(15,23,42,.08);
                         display: flex; flex-direction: column; gap: 12px;
                         min-width: 0; overflow-wrap: break-word;
                       }
                       .card:hover { box-shadow: 0 4px 12px rgba(15,23,42,.08); transform: translateY(-1px); }
                       .panel { /* 同 card，可作为容器使用 */ }
                       ```
                       规则：卡片内部必须用 flex column + gap，禁止用绝对定位或 float；禁止卡片嵌套超过两层。

                   D3. 输入框/表单 .form-item
                       ```css
                       .form-item { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
                       .form-item label { font-size: 13px; font-weight: 500; color: #334155; }
                       .form-item input, .form-item select, .form-item textarea {
                         height: 40px; padding: 8px 12px; border: 1px solid #cbd5e1;
                         border-radius: 8px; font-size: 14px; color: #0f172a; background: #fff;
                       }
                       .form-item input:focus, .form-item select:focus, .form-item textarea:focus {
                         outline: none; border-color: #3b82f6;
                         box-shadow: 0 0 0 2px #fff, 0 0 0 4px rgba(59,130,246,.25);
                       }
                       ```
                       规则：复杂表单使用两列 grid（gap 18px）；移动端自动单列；连续表单项 gap 16~18px。

                   D4. 表格 .data-table
                       ```css
                       .table-scroll { width: 100%; overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 10px; }
                       .data-table { width: 100%; border-collapse: collapse; min-width: 640px; }
                       .data-table th, .data-table td { padding: 12px 20px; text-align: left; border-bottom: 1px solid #e2e8f0; }
                       .data-table th { font-size: 13px; font-weight: 600; color: #475569; background: #f8fafc; }
                       .data-table td { font-size: 14px; color: #334155; }
                       .data-table tr:hover td { background: #f1f5f9; }
                       ```
                       规则：表格必须包在 .table-scroll 内；表头 13px/600，正文 14px，行高 48px；状态标签高度 22~24px，字号 12px。

                   D5. 顶部栏 .app-header
                       ```css
                       .app-header {
                         height: 72px; padding: 0 30px; flex-shrink: 0;
                         display: flex; align-items: center; justify-content: space-between;
                         background: #fff; border-bottom: 1px solid #e2e8f0; gap: 16px;
                       }
                       .header-title { font-size: 20px; font-weight: 600; color: #0f172a; }
                       .header-actions { display: flex; align-items: center; gap: 12px; }
                       ```

                   D6. 侧边栏 .sidebar
                       ```css
                       .sidebar {
                         width: 248px; flex-shrink: 0; min-height: 100vh;
                         background: #132947; color: #e2e8f0; border-right: 1px solid #203654;
                         display: flex; flex-direction: column;
                       }
                       .sidebar-header { height: 76px; padding: 0 20px; display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
                       .nav-list { padding: 22px 12px; display: flex; flex-direction: column; gap: 4px; }
                       .nav-item {
                         height: 42px; padding: 0 12px; border-radius: 8px;
                         display: flex; align-items: center; gap: 10px;
                         font-size: 13px; font-weight: 500; color: #cbd5e1; cursor: pointer;
                       }
                       .nav-item:hover { background: rgba(255,255,255,.08); }
                       .nav-item.active { background: rgba(255,255,255,.12); color: #fff; }
                       ```
                       规则：侧边栏宽度固定 248px，flex-shrink:0；导航项高度 42px；图标尺寸 16~18px，非必要不加。

                   D7. 移动端 .mobile-shell
                       ```css
                       .mobile-shell {
                         width: 390px; max-width: 100%; margin: 0 auto; height: auto;
                         background: #FAFAFA; padding-bottom: 0;
                         display: flex; flex-direction: column; min-width: 0;
                       }
                       .mobile-header {
                         height: 56px; padding: 0 16px; flex-shrink: 0;
                         display: flex; align-items: center; justify-content: space-between;
                         background: #fff; border-bottom: 1px solid #e5e7eb;
                       }
                       .mobile-content { padding: 16px; display: flex; flex-direction: column; gap: 16px; min-width: 0; }
                       .mobile-tabbar {
                         height: 64px; flex-shrink: 0; padding-bottom: env(safe-area-inset-bottom);
                         display: flex; align-items: center; justify-content: space-around;
                         background: #fff; border-top: 1px solid #e5e7eb;
                       }
                       ```
                       规则：移动端禁止 sidebar / side-nav / nav-sidebar 作为底部导航 class；内容区禁止 min-height:100vh；无 TabBar 时底部不留空白。

                E. 登录/注册页内容规则
                   E1. 登录页必须保持轻量：品牌/系统名、1 句说明、账号输入、密码输入、记住/忘记密码、主按钮、1 个次要入口即可。
                   E2. 禁止在登录页左侧或底部堆叠功能模块、规则分析、配置管理、部署说明、长列表、表格或大段介绍。
                   E3. 注册页只展示必要字段；除非用户明确要求，不要出现复杂权益卡片、营销模块、产品功能介绍区。
                   E4. 主按钮宽度与表单对齐，高度 40px；表单卡片 padding 28~32px。

                F. 字号与文字
                   F1. 页面 H1 22~24px，H2 18~20px，H3/卡片标题 16px，正文 14px，辅助文字 12~13px。
                   F2. 正文颜色 #0f172a，辅助文字 #64748b，占位/弱提示 #94a3b8。
                   F3. 行高：标题 1.25~1.35，正文 1.55~1.7，表格 1.4。
                   F4. 禁止 viewport 线性缩放字号；禁止负 letter-spacing；禁止为了塞内容把字调到 10px。

                G. 用户规范/参考图覆盖规则
                   G1. 用户输入其他设计规范时，只覆盖颜色、质感、圆角、阴影、按钮外观、卡片风格、字体气质等视觉 token。
                   G2. 上传参考图时，同样只覆盖视觉 token；默认不覆盖侧栏宽度、表格行高、按钮高度、主内容 padding、登录页内容结构。
                   G3. 只有用户明确写“按参考图布局/侧边栏要更宽/按钮要更大/登录页要展示功能介绍”时，才允许改变布局尺寸和内容复杂度。

                H. 禁止项
                   H1. 禁止 emoji、图标墙、无意义 SVG 插画、满屏渐变、超大英雄区、营销落地页式排版。
                   H2. 禁止元素重叠、文字溢出、按钮文字挤压、横向滚动失控。
                   H3. 除非用户明确要求，否则不要改变上述布局尺寸和组件规格。
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
                6. Web 端主色 #2457d6，App/小程序端主色 #0bb6c7（若有参考图则优先跟随参考图主色）
                7. 不要生成图标墙、装饰性 icon 列表或大量小徽章；更像真实产品原型，而不是素材拼贴页。
                8. 【移动端紧凑原则】App/小程序页面高度由内容自然决定，禁止 min-height:100vh；禁止为了填满屏幕而故意拉大 padding/margin/行高/卡片间距；内容少则紧凑结束，不留底部空白。

                【输出格式】
                - 只输出 HTML 代码
                - 第一个非空白字符必须是 '<'
                - 推荐以 <!DOCTYPE html> 开头
                - 禁止 markdown、禁止前言、禁止结尾说明
                """.formatted(
                getStyleGuide(platform),
                buildGenerationStrategy(platform, hasReferenceImage),
                buildPlatformDesignSpec(platform),
                buildComponentSpecification(),
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
                8. 保持真实业务系统质感：少图标、强结构、清晰表格/列表/表单/导航。不要在每张卡片前都放图标。

                【重要】
                请只输出 JSON 数组，不要包含 ```json 标记，不要添加任何解释文字。
                输出的第一个字符必须是 '['。
                每个页面的 html 字段内禁止只写 Tailwind 类名而不写 CSS。
                """.formatted(style, buildGenerationStrategy(platform, hasReferenceImage), buildPlatformDesignSpec(platform),
                buildComponentSpecification(), buildCssRequirements(),
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

    private static final String EDIT_PATCH_SYSTEM_PROMPT = """
            你是一位资深前端工程师，负责把产品经理的自然语言修改要求转成可立即执行的 DOM patch 指令流。

            硬性规则：
            1. 只输出 JSON Lines：每一行必须是一个完整 JSON 对象。
            2. 禁止 markdown 代码块，禁止解释文字，禁止输出完整 HTML。
            3. 每条指令要尽量小，方便前端收到一条就立刻应用到 iframe。
            4. 只能使用这些 op：
               - setStyle: {"op":"setStyle","selector":"button","all":true,"property":"background-color","value":"#1677ff","summary":"修改按钮颜色"}
               - setText: {"op":"setText","selector":"h1","value":"新标题","summary":"修改标题文案"}
               - setAttr: {"op":"setAttr","selector":"a.primary","name":"href","value":"#page-2","summary":"修改链接"}
               - addClass: {"op":"addClass","selector":".card","all":true,"value":"highlight","summary":"增加样式类"}
               - removeClass: {"op":"removeClass","selector":".card","all":true,"value":"highlight","summary":"移除样式类"}
               - insertHtml: {"op":"insertHtml","selector":"main","position":"beforeend","html":"<section>...</section>","summary":"新增区块"}
               - replaceHtml: {"op":"replaceHtml","selector":".hero","html":"<section class=\\"hero\\">...</section>","summary":"替换区块"}
               - remove: {"op":"remove","selector":".decorative-icons","all":true,"summary":"删除多余装饰"}
               - summary: {"op":"summary","text":"已完成按钮颜色调整"}
            5. selector 必须是浏览器 querySelector/querySelectorAll 可执行的 CSS 选择器。优先使用当前 HTML 里已有的 class/tag/层级。
            6. 修改“所有按钮”时 selector 使用 button,.btn,[role='button'],input[type='button'],input[type='submit'] 且 all=true。
            7. 修改 CSS 属性时 property 使用 kebab-case，例如 background-color、border-radius、font-size。
            8. 不要输出无意义图标、emoji、装饰性 SVG；如果用户要求“更好看”，优先修改布局、间距、颜色、字号、圆角和阴影。
            9. 最后一行输出 summary，说明本次做了什么。
            """;

    /** AI 编辑用的系统提示词 */
    public String getEditSystemPrompt() {
        return EDIT_SYSTEM_PROMPT;
    }

    /** AI 流式局部编辑用的系统提示词：输出 JSON Lines patch。 */
    public String getEditPatchSystemPrompt() {
        return EDIT_PATCH_SYSTEM_PROMPT;
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

    /**
     * 构建「流式 patch 指令」提示词。
     * 输出必须是 JSON Lines，后端会逐行解析并推送给前端 iframe 执行。
     */
    public String buildEditPatchPrompt(String currentHtml, String instruction, String targetElement) {
        String scope = (targetElement == null || targetElement.isBlank())
                ? "未指定具体元素，请根据描述自行定位。"
                : "优先修改这个元素：" + targetElement;

        return """
                这是当前的 HTML 原型代码：
                ------- 当前 HTML 开始 -------
                %s
                ------- 当前 HTML 结束 -------

                【修改需求】
                %s

                【修改范围】
                %s

                【输出要求】
                1. 只输出 JSON Lines，一行一个 JSON 对象。
                2. 不要输出 markdown、不要输出完整 HTML、不要输出解释文字。
                3. 每行必须能被 JSON.parse 直接解析。
                4. 能用 setStyle/setText/setAttr 完成时，不要 replaceHtml 大块替换。
                5. 如果某条需求会影响多个元素，请分多条输出，让前端可以逐步看到变化。
                """.formatted(currentHtml, instruction, scope);
    }

    // ==================== 视觉风格指南 ====================

    private String getStyleGuide(Platform platform) {
        return switch (platform) {
            case APP -> """
                    【终端约束】App 端设计，按手机 App 原型生成，默认画布 390px 居中，高度由内容自然决定。
                    使用移动端顶部栏、内容流、底部 Tab/底部操作栏，禁止生成 Web 后台侧边栏；禁止用 min-height:100vh 强制撑高页面。""";
            case WEB -> """
                    【终端约束】桌面端设计，宽度自适应 100%，内容最大宽度按页面类型决定。
                    不要默认强制生成侧边栏；是否使用顶部栏、侧边栏、右侧面板、居中表单或内容阅读区，必须由功能描述和页面类型判断决定。""";
            case PAD -> """
                    【终端约束】Pad 端设计，按平板 App 原型生成，默认画布 820~1024px 居中。
                    优先使用主从双栏、列表-详情、支持面板或 Feed 网格，触控热区不小于 44px。""";
            case MINI_PROGRAM -> """
                    【终端约束】小程序风格，默认画布 390px 居中。
                    使用微信/小程序式顶部标题栏、内容流、底部操作栏或 TabBar，禁止生成 Web 后台侧边栏。""";
        };
    }

    private String buildPlatformDesignSpec(Platform platform) {
        return switch (platform) {
            case APP -> """
                    【APP 端专用设计规范 — 必须优先于通用 Web 规范】
                    规范来源：OPPO / ColorOS 视觉方向 + Apple HIG 的 iOS 触控/可读性原则、Material 移动端触控目标、Ant Design Mobile / TDesign Mobile / WeUI / Vant 的业务移动组件模式。
                    1. 画布与安全区
                       - 使用 .mobile-shell 或 .phone-shell，宽度 390px，max-width:100%，高度由内容自然决定，禁止 min-height:100vh 强制撑高，居中展示。
                       - body 背景 #FAFAFA，手机画布背景 #FAFAFA；内容区 padding 16px。
                       - 顶部和底部使用 safe-area：padding-top: env(safe-area-inset-top)，padding-bottom: env(safe-area-inset-bottom)。
                       - 禁止横向滚动；禁止出现 Web 后台侧边栏、宽表格、桌面大工具栏。
                       - App 端禁止使用 sidebar / side-nav / nav-sidebar 作为底部导航 class；底部导航只能使用 mobile-tabbar / bottom-tab / tabbar。
                    2. 导航结构
                       - 顶部栏 .mobile-header / .app-navbar 高度 44~56px，左右 padding 12~16px，标题 17px/600，返回按钮触控区 44x44。
                       - 底部 TabBar .mobile-tabbar 高度 56~64px，固定底部，3~5 个 Tab；图标可省略，若使用图标必须统一 20~22px。
                       - 页面主内容 .mobile-content 必须为单列纵向内容流，底部距最后一个卡片或按钮 16px 即可结束；仅在存在底部 TabBar/固定操作栏时才留出 72px 空间，无固定导航时禁止留空。
                    3. 组件尺寸
                       - 主要按钮高度 44~48px，圆角 10~12px（OPPO 大按钮可用 32px 胶囊形），整行按钮可 width:100%；每屏最多 1 个强主按钮。
                       - 输入框/选择器/搜索框高度 44~48px，字号 15~16px，label 14px，说明/错误 12~13px。
                       - 列表项高度 52~64px，左右 padding 16px；卡片 padding 14~16px，圆角 12~14px（OPPO 规范大卡片可用 16~24px），卡片间距 12~16px。
                       - 点击目标至少 44x44px；Android/Material 风格控件优先按 48px 触控目标处理。
                       - 步骤/流程卡片内部要紧凑：padding 14~16px，标题 15px/600，说明 13px，间距 4~6px，禁止为了“填满屏幕”而加大高度或 padding。
                    4. 页面类型
                       - 登录/注册：居中品牌 + 表单，字段精简，主按钮整行，底部次要入口；不要展示后台功能介绍和复杂卡片。
                       - 首页/看板：顶部问候/标题 + 搜索/筛选 + 纵向卡片/统计卡；2 列小卡只用于简单指标。
                       - 列表/管理：使用移动列表、卡片列表、筛选 Chips、滑动操作；不要生成 table/data-table/宽表格。每条数据用 cell/card 展示，操作按钮放在卡片底部或右侧。
                       - 详情：标题区 + 信息分组 + 底部固定主操作；正文行高 1.5；信息卡片内部 padding 14~16px，禁止顶部大留白。
                       - 表单流程：步骤用纵向 step 或顶部轻量步骤；表单单列；提交按钮固定底部或跟随表单底部；步骤卡片高度由内容决定，禁止撑高。
                       - 个人中心/设置：头像/账号区 + cell 列表 + 分组标题，遵循 WeUI/Vant cell 模式。
                    5. 视觉风格
                       - 默认采用 OPPO / ColorOS 风格方向：轻盈、水生感、清透背景、柔和层次、圆润但不夸张、信息分组清楚。
                       - 主色使用 OPPO/ColorOS 倾向的清爽蓝绿色：#0bb6c7，页面背景 #FAFAFA，卡片 #FFFFFF，正文 rgba(0,0,0,.90)，辅助文字 rgba(0,0,0,.60)。
                       - 卡片使用低饱和浅色背景或白底细边框，阴影极轻：0 2px 10px rgba(0,0,0,.06)（普通卡片），0 6px 20px rgba(0,0,0,.08)（SnackBar/底部面板）。
                       - 按钮使用圆润矩形或 32px 胶囊形，不使用大面积强渐变；主按钮使用 #0bb6c7，次按钮使用浅色填充或细边框。
                       - 字体气质接近 OPPO Sans / 系统无衬线：标题清晰、正文舒展，避免过小过密。
                       - 不要使用大量 emoji、小图标、复杂插画；移动端靠间距、分组、列表和底部操作形成秩序。
                       - 【关键】禁止为了“填满一屏”而故意加大 padding、margin、行高、卡片高度；内容少则页面直接紧凑结束，底部不留空白。
                    """;
            case MINI_PROGRAM -> """
                    【小程序专用设计规范 — 必须优先于通用 Web 规范】
                    规范来源：WeUI、Vant Weapp、TDesign 小程序组件模式。
                    1. 画布宽度 390px，高度由内容自然决定，禁止 min-height:100vh，内容少则紧凑结束；内容区 padding 16px，页面背景 #f7f8fa。
                    2. 顶部使用小程序标题栏/导航栏，高度 44~56px；常用返回、首页、标题，触控区不小于 44px。
                    3. 底部可用 TabBar 或固定操作栏，高度 56~64px，并使用 safe-area inset；无固定导航时底部不留多余空白。
                    4. 信息承载优先使用 cell、card、form、popup、action-sheet、toast、empty、result；禁止 Web 后台侧边栏和桌面表格。
                    5. 列表项 52~64px，按钮 44~48px，输入框 44~48px，卡片 padding 14~16px，间距 12~16px；禁止为了填满屏幕而加大 padding 或间距。
                    """;
            case PAD -> """
                    【PAD 端专用设计规范 — 必须优先于通用 Web 规范】
                    规范来源：Apple iPadOS Split View / Sidebar、Material 3 canonical layouts 与 Android window size classes。
                    1. 画布与断点
                       - 使用 .tablet-shell 或 .pad-shell，宽度 820~1024px，max-width:100%，min-height:100vh，居中展示。
                       - 以 8 列栅格组织内容：页面 margin 24px，gutter 24px，主内容 padding 24~32px。
                       - 600px 以下退化为单列；600~839px 可双栏均分；840px 以上使用主从/支持面板。
                    2. 推荐布局
                       - 列表-详情：左侧列表 280~340px，右侧详情自适应，适合消息、文档、客户、配置项、审核问题。
                       - 支持面板：主内容约 70%，右侧辅助面板约 30%，适合编辑器 + AI建议/预览/属性面板。
                       - Feed 网格：卡片最小宽 220~260px，2~3 列，适合看板、内容流、模板库。
                       - Split View 可有两到三栏，但每栏必须有清晰标题、选中态和分隔线。
                    3. 导航与控件
                       - 顶部栏高度 64~72px；侧边栏宽 220~280px，不超过 30% 画布宽度。
                       - 按钮/图标/列表点击目标不小于 44px；按钮高度 40~44px，输入框 42~44px。
                       - 表格可用，但行高 48~56px，操作按钮统一尺寸；如果信息较少，优先卡片/列表-详情。
                    4. 页面类型
                       - 登录/注册：居中卡片或左右分栏，卡片宽 420~480px，禁止后台侧栏。
                       - 工作台/管理：侧边栏 + 顶部栏 + 主内容 + 可选右侧面板；不要把侧边栏做成 Web 超宽目录。
                       - 编辑/预览/AI辅助：优先主内容 + 右侧支持面板，右侧面板 280~340px。
                       - 设置：左侧分组导航 + 右侧设置表单；表单项间距 18~22px。
                    5. 视觉风格
                       - 默认采用 iPadOS/Material 平板工作台气质：轻背景、清晰分栏、细分割线、圆角 12px、克制阴影。
                       - 参考图只覆盖颜色、圆角、阴影、字体气质，不改变主从布局，除非用户明确要求。
                    """;
            case WEB -> """
                    【WEB 端专用设计规范】
                    Web 原型继续使用 StyleKit Corporate Clean / 企业简洁风规范；如上传参考图，仅覆盖视觉 token。
                    """;
        };
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }
}
