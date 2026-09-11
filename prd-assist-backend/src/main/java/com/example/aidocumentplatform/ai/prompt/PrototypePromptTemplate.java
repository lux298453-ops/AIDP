package com.example.aidocumentplatform.ai.prompt;

import com.example.aidocumentplatform.model.enums.AssetMode;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import org.springframework.stereotype.Component;

import java.util.List;

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
            你熟练掌握自研扁平组件包 co-*（移动端 OPPO ColorOS 主题 + 桌面端 Corporate Clean 主题），输出的原型必须一次成型、结构清晰、排版稳定、无重叠无堆叠。

            硬性规则（违反会导致页面“没样式/乱版/重叠/看不见文字”）：
            1. 对比度零容忍铁律 —— 文字必须能被看见：
               - 这是一个最高优先级规则：任何带有背景色的容器（卡片、按钮、面板、列表项、顶部栏、底部栏、标签、徽章、选中态、活动态），其内部文字颜色必须与背景色形成明显对比，绝对禁止出现“白底白字”“浅灰底浅灰字”“深蓝底深蓝字”等 invisible text。
               - 浅色/白色背景（#fff、#ffffff、#F7F8FA、#FAFAFA、#f1f5f9、#f7f8fa、#e2e8f0、rgba(255,255,255,x)）上，文字只能用深色：#1A1A2E、#0f172a、#1e293b、#334155、#475569、#666、#64748b、rgba(0,0,0,0.85~0.50)。绝对禁止在白色/浅色背景上使用 #fff、rgba(255,255,255,x) 或任何接近白色的浅色文字。
               - 深色背景（#1A1A2E、#0f172a、#1a2744、#000、#132947、#1e1b4b、rgba(0,0,0,x)）上，文字只能用浅色：#fff、#f1f5f9、#e2e8f0、rgba(255,255,255,0.90~0.70)。
               - 主色/强调色背景（#1BA784、#0bb6c7、#1e40af、#3b82f6、#6200ee）上，文字只能用 #fff 或接近纯白的浅色。
               - 渐变背景、图片背景、半透明背景：取背景主要颜色的亮度，按上面规则选择文字色；若不确定，优先用 #1A1A2E 在浅色部分、#fff 在深色部分，并加 text-shadow 兜底可读性。
               - 状态类必须单独检查：.active / .selected / .current / .disabled / .highlight 等状态若改变背景色，必须同时改变内部文字颜色，禁止只改背景不改文字。
               - 生成完成后必须执行“对比度自检”：遍历每个写了 background / background-color / background-image 的 CSS 规则，检查其直接或间接子元素的 color；若发现文字与背景亮度接近，立即把 color 替换为对比色，否则禁止输出。
            2. 必须输出完整可运行的 HTML 文档：以 <!DOCTYPE html> 开头，包含 <html><head><body>。
            3. 标准业务页面优先使用平台注入的 co-* 扁平组件库 CSS，不重复定义组件样式。
               但沉浸式展示页、角色主视觉、活动视觉、商品特写等非标准场景不能被强行改造成卡片/列表页；允许为画面构图、背景、主体素材位、光效和舞台区域编写必要的局部 CSS。
               用户明确构图的优先级高于组件清单。禁止重写已有 co-* 组件，但可以新增语义明确的场景 class。
            4. 禁止只写 Tailwind/Bootstrap 类名而不引入对应 CSS。默认不要使用 Tailwind/Bootstrap。
            5. 禁止依赖外部 CSS 文件（cdn 链接也不要用，除非同时给出完整内联兜底样式）。
            6. JavaScript 必须内联在 <script> 中，不要引用外部 JS。
            7. 不要输出 markdown 代码块（不要 ```），不要输出解释文字，不要输出思考过程。
            8. 页面必须有清晰布局：背景色、字号、间距、圆角、阴影、主色按钮、卡片层次都要有。
            9. 避免堆砌装饰性小图标、emoji、SVG 图形和图标网格；图标只能用于导航、按钮、状态等明确功能点。
               每个页面最多使用 4 个功能性图标，不能用大量小图标代替信息层级和布局设计。
            10. 溢出与边界隔离铁律 —— 禁止任何元素超出父容器：
                - 所有组件及其子元素必须严格位于父容器的边界内。任何文字、图标或按钮若总宽度超出父容器，应通过缩小字号、换行、使用省略号或截断来处理，绝对禁止任何元素视觉上超出父容器的左右或上下边缘。
                - 所有父容器必须设置 overflow: hidden（或合理的 overflow 裁剪），所有子元素必须在父容器的裁剪范围内活动；禁止任何子元素（文字、图标、装饰线）视觉上溢出父容器边界。
                - 所有列表/卡片/网格区域必须使用 Flexbox 或 Grid 布局；固定宽度项必须设置 flex-shrink: 0；单行内容禁止折行挤压（flex-wrap: nowrap）；禁止使用 float 或纯 display: block 的自然文档流排列多列内容。
                - 所有动态文本（标题、描述、数值、卡组名）必须设置 white-space: nowrap; text-overflow: ellipsis; overflow: hidden; 或 overflow-wrap: break-word; 禁止因文字过长撑大父容器或与相邻图标/按钮重叠。
                - “数值+图标/按钮”组合（如 128K +）必须使用 Flex 行布局，gap: 4px，整体宽度不得超过父容器右侧边距；父容器使用 display: flex; align-items: center; justify-content: space-between; 禁止使用绝对定位把加号钉在角落。
                - 特别是资源数值旁的加号按钮，必须与数字保持合适间距，并完全包含在父容器内；加号按钮尺寸 16~18px，外层容器 36px，禁止加号比容器大。
            11. 生成前自检 + 生成后对比度巡检：
                - 生成前：逐区域验证顶部栏、活动区、卡组区、列表区、卡片区、底部操作区的父容器是否都定义了固定高度或最小高度？内部元素是否都有明确的水平对齐方式（左/中/右）？overflow 是否已设置？
                - 生成后：再次遍历 <style> 中所有带 background 的规则，检查其默认态 + .active + .selected + .disabled 状态下的文字 color；只要发现白底白字、浅底浅字、主色底主色字，立即修正为对比色。
                - 只要有一个“否”，立即修正布局后再输出，禁止输出带有溢出、重叠、元素超出边界、看不见文字的页面。
            13. 底部 TabBar 数量强制规则（若需求涉及 TabBar）：
                - 如果用户明确说了“底部导航栏 N 个 tab”或类似表达，N 是硬性数字，AI 必须生成恰好 N 个 .co-tab-item，禁止以任何理由（常见 App 做法、美观、空间）增减。
                - 生成 HTML 前，AI 必须先在脑中“提取 Tab 数量”：把需求中提到的数字记下来，并在 HTML 中以 data-tab-count="N" 属性标注在 .co-tabbar 上，方便校验。
                - 输出前必须数 .co-tab-item 的实际数量，若与 data-tab-count 不一致，立即修正。
                - 所有 Tab 必须单行显示：.co-tabbar 必须 flex-wrap: nowrap; overflow: hidden; .co-tab-item 必须 flex: 1 1 0%; min-width: 0; overflow: hidden; .co-tab-label 必须 white-space: nowrap; text-overflow: ellipsis; overflow: hidden; 必要时启用 .dense 类缩小字号到 9px。
                - 禁止出现“3 个 Tab 排第一行，剩下 2 个被挤到第二行”的情况；生成后必须自检 .co-tabbar 是否只有一行。
             14. 卡片紧凑原则 —— 内容少则卡片小，禁止浪费空间：
                 - 卡片高度必须由内容自然决定，禁止设置固定高度或 min-height 撑高卡片。
                 - 如果卡片内只有 1~2 行文字（如仅标题+数量），padding 应缩小到 10~12px，卡片整体高度应紧凑（40~56px），禁止使用 16px 大 padding 制造空旷感。
                 - 多卡片列表场景下，每张卡片高度应独立由各自内容决定，禁止所有卡片统一高度导致内容少的卡片出现大片空白。
                 - 卡片内文字行数少时，行高应使用 1.2~1.3（紧凑），禁止使用 1.5+ 的宽松行高浪费垂直空间。
                 - 卡片间距（gap）应根据内容密度调整：内容紧凑时 gap 用 8px，内容正常时 gap 用 10~12px，禁止固定大间距。
                 - 生成后自检：检查每张卡片内部是否有超过 30% 的空白区域（padding 过大、行高过大、多余空行），如有则缩小 padding 和行高。
             15. 一次性输出要求：
                 - 不要等前端反馈再补样式，必须在第一次响应中就给出可直接查看的完整原型。
                 - 所有容器必须有明确宽度/高度或 flex 自适应规则，禁止出现"无尺寸盒子导致内容溢出或塌陷"。
                 - 所有文字必须包裹在有 padding 的容器内，禁止文字直接贴边或与其他文字重叠。
                 - 所有并列元素之间必须有 gap 或 margin，禁止出现元素紧贴甚至重叠。
             """;

    /**
     * 根据原型类型分发到不同的 prompt 构建方法。
     *
     * @param hasReferenceImage 是否附带风格参考图（影响风格说明段）
     */
    public String buildUserPrompt(String description, Platform platform, PrototypeType type) {
        return buildUserPrompt(description, platform, type, false, null, AssetMode.AUTO, null, null);
    }

    public String buildUserPrompt(String description, Platform platform, PrototypeType type,
                                  boolean hasReferenceImage, String referenceFileName) {
        return buildUserPrompt(description, platform, type, hasReferenceImage, referenceFileName, AssetMode.AUTO, null, null);
    }

    public String buildUserPrompt(String description, Platform platform, PrototypeType type,
                                  boolean hasReferenceImage, String referenceFileName,
                                  AssetMode assetMode,
                                  List<String> clarificationAnswers,
                                  String generationBrief) {
        return type == PrototypeType.MULTI_PAGE
                ? buildMultiPagePrompt(description, platform, hasReferenceImage, referenceFileName, assetMode, clarificationAnswers, generationBrief)
                : buildSinglePagePrompt(description, platform, hasReferenceImage, referenceFileName, assetMode, clarificationAnswers, generationBrief);
    }

    private String buildReferenceStyleHint(boolean hasReferenceImage, String fileName) {
        if (!hasReferenceImage) return "";
        String name = (fileName == null || fileName.isBlank()) ? "用户上传的参考图" : fileName;
        return """

                【风格参考图】
                用户上传了一张风格参考图（文件名：%s）。
                - 若你能看到图片：请提取其主色、辅助色、背景质感、圆角、阴影、边框、按钮形状、卡片质感、字体气质和列表密度，并通过 co-* 组件 class 组合实现同等视觉效果（禁止新增 <style>）。
                - 参考图默认只覆盖视觉风格，不强行覆盖页面布局；只有当用户描述明确要求“按参考图布局/复刻布局/照着图片结构”时，才尽量复用参考图结构。
                - 若你无法查看图片：仍按现代、干净、留白充足的产品设计风格生成，主色 #409EFF。
                - 不要在 HTML 中嵌入该图片本身，只借鉴其视觉风格。
                - 保持交互完整与可读性，优先可用性。
                - 不要因为有参考图就生成大量图标；参考图主要用于视觉风格，不是让你复刻装饰元素数量。
                """.formatted(name);
    }

    private String buildCssRequirements() {
        return """
                【CSS 规则 — 与平台设计系统配合（最高优先级）】
                1. 平台已注入完整组件库 CSS：清单中所有 co-* class（含 .co-* 子元素）都已具备完整样式，禁止为它们编写任何重复样式。
                2. 标准业务组件直接使用 co-*；沉浸式主视觉、角色展示、商品特写、活动舞台、特殊背景与光效允许编写完成构图所需的局部 <style>。不要为了少写 CSS 而把特殊画面退化成普通卡片。
                3. 排版硬性要求（无论是否写 CSS 都必须满足，平台质量兜底样式会二次校验）：
                   - 按钮/标签/胶囊类元素：内部文字必须水平垂直居中，水平内边距至少 15px；文字禁止贴边、禁止被圆角裁切、禁止溢出。
                   - 所有文字必须位于有 padding 的容器内，与容器边缘距离至少 12px（标签、徽章等小元素除外）。
                   - 所有并列元素之间必须有 gap 或 margin（≥8px），禁止元素紧贴或重叠。
                   - 列表/卡片流：相邻卡片/列表项之间必须有间距（≥8px）。
                   - 导航栏标题居中；头像内文字或首字居中显示。
                   - 文字颜色必须与背景形成明显对比：白/浅色背景 → 深色文字；深色背景 → 浅色文字；主色背景 → 白色文字。禁止白底白字、浅底浅字。
                   - 文本容器必须 overflow-wrap: break-word；单行文本使用 white-space:nowrap + text-overflow:ellipsis；flex 子项设置 min-width:0。
                   - 容器有边框/圆角/阴影/背景时，内容必须被完全包裹在容器内，禁止溢出。
                4. 若确需补充少量样式，必须写在 <head> 内的 <style> 中，并使用真实 CSS 选择器，禁止空 class 无样式。
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
                - 高度规则：桌面端与移动端页面均自然适应视口；移动端建议使用 min-height: 100% 铺满视口，保证完整的页面感。
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
                   A2. 移动端页面建议使用 min-height: 100% 自然延展铺满视口，保持页面背景统一，操作按钮与核心内容结构清晰。
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
                   C3. CRUD 表格页：page-title 区 + toolbar/filter-bar + table-panel；表格操作列使用 action-cell/table-actions。
                        - table-panel 内部结构必须严格为：<div class="table-area"><div class="table-scroll"><table class="data-table">...</table></div></div> + <div class="table-foot"><div class="pagination">...</div></div>
                        - .table-foot 必须使用 margin-top:18px、padding-top:16px、border-top 与表格区隔开，禁止分页与表格底部重叠。
                        - data-table 必须设置 table-layout: fixed; 并通过 th class 固定列宽（.col-check / .col-id / .col-actions 等）。
                        - pagination 中“上一页/下一页”按钮高度 34px，字号 13px，禁止做成大按钮。
                   C4. 表单流程页：steps/step-list 在顶部，step-item padding 14px 16px；下方 form-panel 两列表单，右侧 summary-panel。
                   C5. 内容详情页：detail-panel 阅读宽 720~960px，padding 24px；目录/摘要面板 280~320px。
                   C6. 数据看板页：metric-card + chart panel + table-panel；图表面板 padding 20px，图表周围留白至少 18px。
                   C7. 设置页：form-panel 分组布局，每个 setting-card padding 20px，开关/按钮右对齐。
                   C8. 弹窗/抽屉：modal-card padding 24px，footer 操作区 gap 12px；空/错/成功状态使用 center-state padding 30px。

                D. StyleKit Corporate Clean 组件食谱（必须按此写 CSS）
                   以下给出的数值是默认值，参考图/用户规范只能覆盖颜色、质感、圆角、阴影、字体气质，不要覆盖尺寸和内部间距。

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

                   D4. 表格 .data-table + 分页 .pagination（防重叠/防错位重点）
                        ```css
                        /* 表格面板：让表格和分页在一个垂直 flex 容器内，保证间距 */
                        .table-panel {
                          display: flex; flex-direction: column; gap: 0;
                          background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
                          padding: 20px; box-shadow: 0 1px 3px rgba(15,23,42,.08);
                        }
                        .table-panel .table-area { width: 100%; }
                        .table-panel .table-foot {
                          width: 100%;
                          margin-top: 18px;
                          padding-top: 16px;
                          border-top: 1px solid #e2e8f0;
                          display: flex; align-items: center; justify-content: flex-end;
                          gap: 8px; flex-wrap: wrap;
                          min-height: 36px;
                        }
                        .table-scroll {
                          width: 100%; overflow-x: auto; overflow-y: hidden;
                          border: 1px solid #e2e8f0; border-radius: 10px;
                        }
                        .data-table {
                          width: 100%; border-collapse: collapse; table-layout: fixed; min-width: 640px;
                          font-size: 14px; line-height: 1.5;
                        }
                        .data-table th, .data-table td {
                          padding: 12px 20px; text-align: left; vertical-align: middle;
                          border-bottom: 1px solid #e2e8f0;
                          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                        }
                        /* 多行文本列放开换行，但仍要垂直居中 */
                        .data-table td.wrap { white-space: normal; overflow-wrap: break-word; }
                        .data-table th { font-size: 13px; font-weight: 600; color: #475569; background: #f8fafc; }
                        .data-table td { color: #334155; }
                        .data-table tr:last-child td { border-bottom: none; }
                        .data-table tr:hover td { background: #f1f5f9; }
                        /* 操作列统一宽度，禁止各列参差不齐 */
                        .data-table .col-actions { width: 160px; text-align: right; }
                        .data-table .col-check { width: 48px; text-align: center; }
                        .data-table .col-id { width: 72px; text-align: left; }

                        /* 分页：必须放在 .table-foot 内，与表格顶部有明确分隔 */
                        .pagination {
                          display: flex; align-items: center; justify-content: flex-end;
                          gap: 8px; flex-wrap: wrap;
                          min-height: 34px; padding-top: 0; margin-top: 0;
                        }
                        .pagination .page-btn, .pagination .page-prev, .pagination .page-next {
                          display: inline-flex; align-items: center; justify-content: center;
                          height: 34px; min-width: 34px; padding: 0 10px;
                          border: 1px solid #e2e8f0; border-radius: 8px;
                          background: #fff; color: #334155; font-size: 13px; font-weight: 500;
                          white-space: nowrap; cursor: pointer; transition: all 150ms ease-out;
                        }
                        .pagination .page-btn:hover, .pagination .page-prev:hover, .pagination .page-next:hover {
                          background: #f8fafc; border-color: #cbd5e1;
                        }
                        .pagination .page-btn.active {
                          background: #1e40af; border-color: #1e40af; color: #fff;
                        }
                        .pagination .page-info { font-size: 13px; color: #64748b; margin-right: 8px; }
                        ```
                        规则：
                        - 表格必须包在 .table-scroll 内，.table-scroll 必须包在 .table-area 内，.table-area 必须和 .pagination 一起包在 .table-panel 内。
                        - .table-panel 内 .table-foot 必须使用 margin-top:18px + padding-top:16px + border-top 与表格区隔开，绝对禁止分页贴到表格底部或重叠。
                        - 必须给 .data-table 加 table-layout: fixed; 保证各列对齐；每列通过 col 或 th class 明确宽度（如 .col-id、.col-actions），禁止让 AI 自由决定列宽。
                        - th、td 必须统一 vertical-align: middle; text-align: left; padding 12px 20px；禁止同一张表里不同列的 padding 或对齐方式不一致。
                        - 分页按钮高度固定 34px，最小宽度 34px，字号 13px；禁止把“上一页/下一页”做成 40px+ 的大按钮。
                        - 分页容器 .pagination 必须放在 .table-foot 内，使用 justify-content: flex-end（或 center）并带 gap:8px，禁止分页按钮挤在表格边框内或紧贴表格。
                        - 表格操作列按钮必须使用 action-cell/table-actions 包裹，与 D1 按钮食谱同高（34~40px），同 padding，禁止忽大忽小。
                        - HTML 结构必须严格如下：
                          <div class="table-panel">
                            <div class="table-area"><div class="table-scroll"><table class="data-table">...</table></div></div>
                            <div class="table-foot"><div class="pagination"><button class="page-prev">上一页</button>...</div></div>
                          </div>

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
                       规则：移动端禁止 sidebar / side-nav / nav-sidebar 作为底部导航 class；保持移动端清晰版面。

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
                                         boolean hasReferenceImage, String referenceFileName,
                                         AssetMode assetMode,
                                         List<String> clarificationAnswers,
                                         String generationBrief) {
        return """
                请根据以下功能描述，生成一个完整的、可直接在浏览器中打开的 HTML 原型页面。

                %s
                %s
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
                2. 所有 JS 写在 <script> 内
                3. 包含逼真的模拟数据
                4. 实现核心交互（按钮点击、Tab 切换、列表滚动、简单表单反馈等）
                5. 不要生成图标墙、装饰性 icon 列表或大量小徽章；更像真实产品原型，而不是素材拼贴页。
                6. 【移动端版面原则】App/小程序页面默认宽度 390px 居中展示，页面背景与主容器建议自然延展铺满视口（min-height: 100%），保持标准移动端页面的完整版面感，包含顶部、主体与操作区，禁止生成孤立的小碎片。

                【输出格式】
                - 只输出 HTML 代码
                - 第一个非空白字符必须是 '<'
                - 推荐以 <!DOCTYPE html> 开头
                - 禁止 markdown、禁止前言、禁止结尾说明
                """.formatted(
                getStyleGuide(platform),
                buildGenerationStrategy(platform, hasReferenceImage),
                buildInternalStructureInstruction(platform, description),
                buildPlatformDesignSpec(platform),
                PrototypeDesignSystem.componentListFor(platform),
                buildCssRequirements(),
                buildAssetAndClarificationInstruction(assetMode, clarificationAnswers, generationBrief),
                buildReferenceStyleHint(hasReferenceImage, referenceFileName),
                description);
    }

    // ==================== 多页面 ====================

    private String buildMultiPagePrompt(String description, Platform platform,
                                         boolean hasReferenceImage, String referenceFileName,
                                         AssetMode assetMode,
                                         List<String> clarificationAnswers,
                                         String generationBrief) {
        String style = getStyleGuide(platform);
        return """
                请根据以下功能描述，生成一个多页面原型。页面数量只按用户明确需求和最终生成简报决定，通常 2~5 页；不得机械补齐无关页面。

                %s
                %s
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
                    "html": "<!DOCTYPE html><html><head><meta charset=\\"UTF-8\\"></head><body>...完整页面...</body></html>"
                  }
                ]

                【页面设计要求】
                1. 每个页面的 html 都必须是完整可运行文档
                2. 页面之间必须可跳转：给跳转按钮/链接写 data-proto-page="目标order"
                   例如：从登录页去首页 → <button data-proto-page="2">进入首页</button>
                   也可使用 href="#page-2" 或 href="#首页"
                3. 第一个页面(order=1)作为首页或导航入口
                4. 页面集合必须来自用户需求。只有业务应用明确需要时才包含登录、首页、列表、详情或设置；壁纸主题、角色主题、视觉套装等展示需求应生成对应的锁屏、主屏、组件页等，不得改造成业务应用。
                5. 所有页面视觉体系统一（配色、圆角、字号、按钮样式一致）
                6. 包含逼真模拟数据
                7. 每个页面是独立 HTML，不要把多个页面的 DOM 塞进同一个 html 字段
                8. 保持真实业务系统质感：少图标、强结构、清晰列表/表单/导航。不要在每张卡片前都放图标。
                9. 多页仅表示存在多个独立页面，不等于后台或业务应用。每页结构仍由该页意图决定；沉浸式壁纸、主题预览和角色展示可以不使用 .co-page-shell、导航栏、卡片或业务组件。
                10. 如果系统提供了核心视觉素材占位符，并且简报要求主体贯穿每页，则每个相关页面都必须各自放置一次该占位符；同一素材允许跨页面复用，由页面 CSS 调整位置、尺寸和层级。

                【重要】
                请只输出 JSON 数组，不要包含 ```json 标记，不要添加任何解释文字。
                输出的第一个字符必须是 '['。
                """.formatted(style, buildGenerationStrategy(platform, hasReferenceImage),
                buildInternalStructureInstruction(platform, description),
                buildPlatformDesignSpec(platform),
                PrototypeDesignSystem.componentListFor(platform),
                buildCssRequirements(),
                buildAssetAndClarificationInstruction(assetMode, clarificationAnswers, generationBrief),
                buildReferenceStyleHint(hasReferenceImage, referenceFileName), description);
    }

    private String buildInternalStructureInstruction(Platform platform, String description) {
        return """
                【结构判断已由系统接管】
                - 前端不会再给你“页面形态”选项，你必须只根据平台和需求内容，自行判断这是完整页面、局部模块、弹窗浮层、卡片组件还是多区块工作台。
                - 如果用户描述的是“精灵展示弹窗、奖励弹窗、卡片特写、局部组件”，就只生成该核心区域，不要私自补侧边栏、顶部栏、底部导航或后台框架。
                - 如果用户描述的是“登录、注册、首页、工作台、列表、详情、设置、审批、商城”等完整场景，再生成完整页面骨架。
                - 如果需求已经明确写了元素位置、主视觉、按钮、标题、比例或主体对象，优先忠实执行这些要求，不要为了套模板而改成另一种页面。
                - 当前平台为 %s，请在这个平台规范内做结构判断。
                - 当前原始需求如下：%s
                """.formatted(platform.name(), description);
    }

    private String buildAssetAndClarificationInstruction(
            AssetMode assetMode, List<String> clarificationAnswers, String generationBrief) {
        String modeText = switch (assetMode == null ? AssetMode.AUTO : assetMode) {
            case NONE -> "禁止依赖外部图片素材，只使用纯布局、渐变、色块、占位图和内置样式完成页面。";
            case UPLOAD_ONLY -> "优先使用用户上传的参考图所体现的视觉风格，不要再假设外部网络图片。";
            case BUILT_IN -> "优先使用系统内置素材思路和占位资源，不要过度依赖网络图片。";
            case NETWORK -> "允许为商品图、人物图、插画图预留真实素材位，但布局必须先成立，不能因为图片缺失导致页面混乱。";
            case AUTO -> "素材使用由系统自动判断：先保证结构和排版成立，再决定是否需要真实图片位。";
        };
        StringBuilder builder = new StringBuilder();
        builder.append("【需求理解与生成简报 — 高优先级】\n");
        if (generationBrief != null && !generationBrief.isBlank()) {
            builder.append(generationBrief.trim()).append("\n");
        } else {
            builder.append("以用户原始描述为最高事实来源，只补齐可用性细节，不增加未要求的业务模块。\n");
        }
        builder.append("- ").append(modeText).append("\n");
        if (clarificationAnswers != null && !clarificationAnswers.isEmpty()) {
            builder.append("- 以下是用户刚刚确认的信息，必须合并到上面的生成简报：\n");
            for (String answer : clarificationAnswers) {
                if (answer == null || answer.isBlank()) continue;
                builder.append("  - ").append(answer.trim()).append("\n");
            }
        }
        builder.append("- 忠实度规则：用户没提到的导航、卡片、属性数值、说明段落、统计数据、功能入口和装饰图标，一律不要自行添加。\n");
        builder.append("- 构图规则：用户明确了主体位置、背景覆盖、光效方向、按钮数量或文字时，逐项照做，不得用常规页面模板替换。\n");
        return builder.toString().trim();
    }

    /**
     * 构建「页面形态约束」指令：注入固定骨架 + 填充规则。
     * AUTO 形态不注入骨架，AI 自行判断。
     */
    private String buildMorphologyInstruction(com.example.aidocumentplatform.model.enums.PageMorphology morphology) {
        if (morphology == null || morphology == com.example.aidocumentplatform.model.enums.PageMorphology.AUTO) {
            return """
                    【页面形态约束 — 自由模式】
                    根据功能描述自行判断页面结构。若描述的是弹窗/浮层/卡片类组件，只生成组件本身，不生成页面外壳和导航栏；
                    若描述的是完整页面（首页/列表/详情/商城/扭蛋机等），生成完整页面结构。
                    """;
        }
        String shell = PrototypeDesignSystem.shellFor(morphology);
        String fillRule = PrototypeDesignSystem.fillRuleFor(morphology);
        return """
                【页面形态约束 — 已选择：%s】
                骨架结构已由平台固定，你必须严格按照以下骨架输出 HTML（骨架的 class 样式已由平台注入，禁止修改骨架结构，禁止写 <style>）：
                ```html
                %s
                ```
                %s
                禁止新增骨架之外的页面外壳、导航栏、标签栏；禁止发明清单外的 class。
                """.formatted(morphologyLabel(morphology), shell, fillRule);
    }

    private String morphologyLabel(com.example.aidocumentplatform.model.enums.PageMorphology m) {
        return switch (m) {
            case FULL_PAGE -> "完整页面";
            case MODAL_POPUP -> "弹窗/浮层";
            case LIST_FEED -> "列表/信息流";
            case FORM_FLOW -> "表单/流程";
            case COMPONENT_ONLY -> "组件特写";
            case AUTO -> "自由模式";
        };
    }

    private static final String EDIT_SYSTEM_PROMPT = """
            你是一位资深前端工程师，负责根据产品经理的自然语言指令修改现有 HTML 原型。修改必须以用户最新指令为准，而不是机械保留旧模板。
            规则：
            1. 小范围样式或文案需求使用最小必要修改；若用户要求改变构图、页面结构、内容密度、沉浸感、主体位置，允许重构相关区域乃至整个 body，并删除与新意图冲突的旧卡片、导航、说明和数据。
            2. 保持单文件结构，所有样式与脚本继续内联；若新增元素，必须使用现有 co-* 组件 class（样式已由平台注入），禁止重写组件样式；确需额外样式时才补充 <style>，且不得覆盖 co-* 组件。
            3. 输出必须是可直接运行的完整 HTML（含 <!DOCTYPE html> 与完整 <style>）。
            4. 不要输出 markdown 代码块，不要输出解释文字。
            5. 若用户描述“背景铺满、主体居中、顶部打光、主体下方只有一个按钮”，必须逐项实现，并删除未要求的说明卡片、属性数值、导航和长文案；不能只换颜色或移动一个按钮就算完成。
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

                【执行判断】
                - 若这是颜色、字号、文字、单个间距等局部调整，保留无关内容。
                - 若这是“改成某种画面 / 重新布局 / 背景铺满 / 主体居中 / 只保留某些元素 / 删除多余模块”等结构性调整，必须重构受影响区域，旧结构与新要求冲突时直接删除旧结构。
                - 完成前逐项核对修改需求中的每个名词、位置词、数量词和禁止词，不得只完成其中一部分。

                【图片占位符】
                HTML 中形如 __PROTO_IMG_1__ 的字符串是内嵌图片的占位符（原文是体积巨大的 Data URL，已被系统摘出）。
                除非修改需求明确要求删除对应图片，否则必须把占位符原样保留在原来的位置（例如 src 属性中），
                禁止改写、拆分、翻译占位符，禁止把它替换成其他 URL 或编造图片地址。

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
                6. HTML 中形如 __PROTO_IMG_1__ 的字符串是内嵌图片的占位符；patch 中需要引用图片时原样使用占位符，禁止改写或替换成其他 URL。
                """.formatted(currentHtml, instruction, scope);
    }

    // ==================== 视觉风格指南 ====================

    private String getStyleGuide(Platform platform) {
        return switch (platform) {
            case APP -> """
                    【终端约束】App 端设计，按手机 App 原型生成，默认画布 390px 居中，页面背景与主容器自然延展铺满视口。
                    使用 OPPO ColorOS 设计语言（O-Flow）：灵动活力、无边界设计、大圆角、轻阴影、高饱和度色彩。
                    使用移动端顶部栏、内容流、底部 Tab/底部操作栏，禁止生成 Web 后台侧边栏。""";
            case WEB -> """
                    【终端约束】桌面端设计，宽度自适应 100%，内容最大宽度按页面类型决定。
                    不要默认强制生成侧边栏；是否使用顶部栏、侧边栏、右侧面板、居中表单或内容阅读区，必须由功能描述和页面类型判断决定。""";
            case PAD -> """
                    【终端约束】Pad 端设计，按平板 App 原型生成，默认画布 1024px 居中（平台已注入）。
                    使用 OPPO ColorOS 平板设计语言：主从双栏、列表-详情、支持面板或 Feed 网格，触控热区不小于 48dp。
                    顶部导航栏 56dp，侧边导航常驻 240~280dp，底部无 TabBar（平板端使用侧边 NavigationRail 替代）。
                    禁止生成手机端底部 TabBar 和 Web 后台超宽侧边栏。""";
            case MINI_PROGRAM -> """
                    【终端约束】小程序风格，默认画布 390px 居中。
                    使用微信/小程序式顶部标题栏、内容流、底部操作栏或 TabBar，禁止生成 Web 后台侧边栏。""";
        };
    }

    private String buildPlatformDesignSpec(Platform platform) {
        return switch (platform) {
            case APP, MINI_PROGRAM -> """
                【平台设计规范 — APP/小程序 — 基础组件样式已由平台注入】
                    1. 主题：OPPO ColorOS（主色 #1BA784，背景 #F7F8FA）。所有 co-* 组件的颜色、圆角、阴影、间距已由平台注入，禁止为它们编写或覆盖任何 CSS，禁止定义 :root 变量。
                    2. 画布：390px 居中（平台已注入），页面背景与主容器建议自然延展铺满视口（min-height: 100%），具备完整清晰的移动端版面感，包含顶部、主体与必要操作区，禁止生成孤立的小碎片卡片。
                    3. 结构由需求意图决定：业务完整页可使用 .co-page-shell > 可选 .co-navbar + .co-page-content + 可选 .co-tabbar；沉浸式展示页、角色主视觉、活动视觉或单一领取场景可使用全屏舞台结构，不强制顶部栏、内容卡片或底部导航。禁止生成 Web 后台侧边栏。
                    4. 反桌面禁区（后台/CRUD/表格/报表描述一律翻译为移动端表达）：禁止 .co-sidebar、co-table 宽表格、分页器、面包屑、横向步骤条、横向标签页、双列表单、桌面大工具栏。
                       - 表格 → .co-list / .co-cell 列表或 .co-card 卡片流
                       - 分页 → 上拉加载 / Tab 切换
                       - 详情 → 单列 .co-card 分组
                       - 后台管理 → 列表 + 卡片详情 + 底部固定操作栏（.co-bottom-action）
                    5. 表单单列；输入用 .co-form-item/.co-input；提交按钮用 .co-btn-primary。展示型页面若用户只要求一个 CTA，就只保留一个 CTA，不补说明卡片、属性栏或额外入口。
                    6. 状态：.active/.selected/.current/.disabled 仅通过 class 表达（平台已注入状态样式），禁止手写状态 CSS。
                    """;
            case PAD -> """
                    【平台设计规范 — PAD — 组件样式已由平台注入（co-* 扁平组件包）】
                    1. 主题：OPPO ColorOS（主色 #1BA784）；画布 1024px 居中（平台已注入），高度由内容自然决定。
                    2. 结构（必须与注入骨架一致）：<div class="app-shell"><aside class="co-sidebar">…</aside><div class="main-wrap"><div class="co-header">…</div><main class="co-page-content">…</main></div></div>
                       - 侧栏 <aside class="co-sidebar">（240px，含 .co-sidebar-brand + .co-sidebar-nav > .co-nav-item）与主区 <div class="main-wrap"> 是兄弟节点、左右并排；禁止把 .co-sidebar 放进 .co-page-shell 或 .co-header 内部（会导致侧栏占满整行把内容挤到下方）。
                       - 禁止手机端底部 TabBar（平板用侧边导航替代）。
                    3. 布局模式：列表-详情（侧栏列表 + 详情区）、主内容+右侧支持面板、Feed 网格（.co-metric-grid / 2~3 列 .co-card）；禁止超宽侧栏（不超过 30% 画布）、禁止手机全屏单列。
                    4. 表单 .co-form-item 可双列（gap 20px）；触控热区 ≥48dp；数据看板用 .co-metric-grid；选择列用 .co-select、附件列用 .co-upload。
                    5. 状态：.active/.selected/.current/.disabled 仅通过 class 表达（平台已注入状态样式），禁止手写状态 CSS。
                    """;
            case WEB -> """
                    【平台设计规范 — WEB — 组件样式已由平台注入（co-* 扁平组件包）】
                    1. 主题：Corporate Clean 企业简洁风（主色 #1e40af，背景 #f8fafc，面板 #ffffff）；宽度自适应 100%。
                    2. 结构：使用侧边栏时必须是 <div class="app-shell"><aside class="co-sidebar">…</aside><div class="main-wrap"><div class="co-header">…</div><main class="co-page-content">…</main></div></div>（侧栏与主区左右并排，禁止把侧栏放进 co-page-shell）；无侧栏页面用 <div class="co-page-shell">。是否使用侧栏由页面类型决定（后台/管理/文档用侧栏，登录/阅读/表单居中）。
                    3. 数据密集场景（后台/CRUD/报表）使用 .co-table；看板用 .co-metric-grid；表单 .co-form-item 可双列；按钮 .co-btn-primary；选择列用 .co-select、附件列用 .co-upload。
                    4. 禁止移动端结构：390px 画布、.co-tabbar 底部导航、固定底部操作栏、手机卡片流替代表格。
                    5. 状态：.active/.selected/.current/.disabled 仅通过 class 表达（平台已注入状态样式），禁止手写状态 CSS。
                    """;
        };
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }
}
