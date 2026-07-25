<script setup lang="ts">
/**
 * 原型结果 —— 右侧区域（实时预览 + 可视化编辑）
 * 面向产品经理：不暴露源代码，通过「点选元素 + 属性面板 + AI 辅助」完成修改。
 *
 * 用法（父组件）：
 *   <PrototypeResultView
 *     v-model="currentHtml"           上下同步的原型 HTML
 *     :prototype-id="resultId"        原型 ID（调用 AI 修改接口用）
 *     :platform="platform"
 *     :regenerating="loading"          重新生成进行中
 *     @regenerate="handleGenerate"
 *     @export="onExported"             (html) => 已导出的完整 HTML
 *   />
 */
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Iphone, Platform, Monitor, RefreshRight, FullScreen, MagicStick,
  Download, Pointer, View, Brush, Rank, Close, CircleCheck,
} from '@element-plus/icons-vue'
import { aiEditPrototype } from '@/api/prototype'

/* ==================== Props / Emits / v-model ==================== */
const html = defineModel<string>({ default: '' })
const props = withDefaults(defineProps<{
  prototypeId: number
  platform?: string
  regenerating?: boolean
  /** 多页标题列表，用于页内跳转桥接 */
  pageTitles?: string[]
  /** 当前页索引（多页） */
  pageIndex?: number
}>(), { platform: 'WEB', regenerating: false, pageTitles: () => [], pageIndex: 0 })

const emit = defineEmits<{
  regenerate: []
  export: [html: string]
  gotoPage: [index: number]
}>()

/* ==================== 注入 iframe 的编辑运行时 ==================== */
// 高亮样式（悬停虚线、选中实线光圈）
const RUNTIME_STYLE = `<style data-proto-runtime>
[data-proto-hover]{ outline:1px dashed #f54e00 !important; outline-offset:-1px; cursor:pointer !important; }
[data-proto-selected]{ outline:2px solid #f54e00 !important; outline-offset:-2px; box-shadow:0 0 0 3px rgba(245,78,0,.2) !important; }
<\/style>`

// 与父页面通信：点选元素、回传属性、应用样式、结构树、拖拽排序、导出干净 HTML
const RUNTIME_SCRIPT = `<script data-proto-runtime>
(function(){
  "use strict";
  var selected=null, counter=0, mode="select";
  function post(t,p){ parent.postMessage({source:"proto",type:t,payload:p}, "*"); }
  function pid(el){ if(!el.getAttribute("data-proto-id")){ el.setAttribute("data-proto-id","p"+(++counter)); } return el.getAttribute("data-proto-id"); }
  function byId(id){ return document.querySelector('[data-proto-id="'+id+'"]'); }
  function toHex(c){ if(!c) return "#000000"; if(c.charAt(0)==="#") return c; var m=c.match(/rgba?\\(([^)]+)\\)/); if(!m) return "#000000"; var a=m[1].split(",").map(function(x){return parseFloat(x)}); if(a.length>=4&&a[3]===0) return "transparent"; function h(n){ n=Math.max(0,Math.min(255,Math.round(n))); var s=n.toString(16); return s.length<2?"0"+s:s; } return "#"+h(a[0])+h(a[1])+h(a[2]); }
  function editableText(el){ for(var i=0;i<el.childNodes.length;i++){ if(el.childNodes[i].nodeType===1) return null; } return el.textContent; }
  function normWeight(w){ if(w==="normal") return "400"; if(w==="bold") return "700"; return String(w); }
  function info(el){ var cs=getComputedStyle(el); var tag=el.tagName.toLowerCase();
    return { id:pid(el), tag:tag, text:editableText(el), href:el.getAttribute("href")||"",
      isLink: el.hasAttribute("href")||tag==="button"||el.getAttribute("role")==="button",
      styles:{ color:toHex(cs.color), backgroundColor:toHex(cs.backgroundColor),
        fontSize:parseInt(cs.fontSize)||14, fontWeight:normWeight(cs.fontWeight), textAlign:cs.textAlign||"left",
        padding:parseInt(cs.paddingTop)||0, margin:parseInt(cs.marginTop)||0,
        borderRadius:parseInt(cs.borderTopLeftRadius)||0,
        boxShadow:(cs.boxShadow&&cs.boxShadow!=="none")?"on":"off" } };
  }
  function select(el){ if(selected) selected.removeAttribute("data-proto-selected"); selected=el; el.setAttribute("data-proto-selected",""); post("select", info(el)); }
  document.addEventListener("mouseover", function(e){ if(mode!=="select") return; var el=e.target; if(el===document.body||el===document.documentElement||!el.setAttribute) return; el.setAttribute("data-proto-hover",""); }, true);
  document.addEventListener("mouseout", function(e){ if(e.target&&e.target.removeAttribute) e.target.removeAttribute("data-proto-hover"); }, true);
  document.addEventListener("click", function(e){ if(mode!=="select") return; e.preventDefault(); e.stopPropagation(); var el=e.target; if(!el||el===document.body||el===document.documentElement) return; select(el); }, true);
  function applyStyle(id,prop,val){ var el=byId(id); if(!el) return;
    if(prop==="padding"){ el.style.padding=val+"px"; }
    else if(prop==="margin"){ el.style.margin=val+"px"; }
    else if(prop==="fontSize"){ el.style.fontSize=val+"px"; }
    else if(prop==="borderRadius"){ el.style.borderRadius=val+"px"; }
    else { el.style[prop]=val; } }
  function clean(){ var d=document.documentElement.cloneNode(true);
    var rt=d.querySelectorAll("[data-proto-runtime]"); for(var i=0;i<rt.length;i++){ if(rt[i].parentNode) rt[i].parentNode.removeChild(rt[i]); }
    var al=d.querySelectorAll("[data-proto-id],[data-proto-hover],[data-proto-selected]");
    for(var j=0;j<al.length;j++){ al[j].removeAttribute("data-proto-id"); al[j].removeAttribute("data-proto-hover"); al[j].removeAttribute("data-proto-selected"); }
    return "<!DOCTYPE html>\\n"+d.outerHTML; }
  function label(el){ var t=el.tagName.toLowerCase(); var x=(el.textContent||"").trim().replace(/\\s+/g," ").slice(0,16); return x? t+" · "+x : t; }
  function tree(){ function walk(el){ var kids=[]; for(var i=0;i<el.children.length;i++){ var c=el.children[i]; if(c.hasAttribute("data-proto-runtime")) continue; kids.push(walk(c)); } return { id:pid(el), label:label(el), children:kids }; }
    var b=document.body, a=[]; if(!b) return a; for(var i=0;i<b.children.length;i++){ var c=b.children[i]; if(c.hasAttribute("data-proto-runtime")) continue; a.push(walk(c)); } return a; }
  function move(id,tid,pos){ var el=byId(id), t=byId(tid); if(!el||!t||el===t||el.contains(t)) return;
    if(pos==="inner"){ t.appendChild(el); } else if(pos==="before"){ t.parentNode.insertBefore(el,t); } else { t.parentNode.insertBefore(el,t.nextSibling); } }
  window.addEventListener("message", function(e){ var d=e.data; if(!d||d.source!=="proto-parent") return;
    if(d.type==="style"){ applyStyle(d.id,d.prop,d.value); }
    else if(d.type==="text"){ var e1=byId(d.id); if(e1) e1.textContent=d.value; }
    else if(d.type==="attr"){ var e2=byId(d.id); if(e2) e2.setAttribute(d.name,d.value); }
    else if(d.type==="mode"){ mode=d.value; if(mode!=="select"){ var hs=document.querySelectorAll("[data-proto-hover]"); for(var i=0;i<hs.length;i++) hs[i].removeAttribute("data-proto-hover"); } }
    else if(d.type==="selectById"){ var e3=byId(d.id); if(e3){ select(e3); e3.scrollIntoView({block:"nearest"}); } }
    else if(d.type==="deselect"){ if(selected){ selected.removeAttribute("data-proto-selected"); selected=null; } }
    else if(d.type==="move"){ move(d.id,d.targetId,d.position); post("tree",tree()); post("html",clean()); }
    else if(d.type==="requestHtml"){ post("html",clean()); }
    else if(d.type==="requestTree"){ post("tree",tree()); } });
  post("ready", true);
})();
<\/script>`

/**
 * 把编辑运行时注入到「完整 HTML 文档」中。
 * 旧实现把 style + 整页 HTML + script 字符串拼接，会破坏 <html>/<head>/<style>，
 * 导致预览无样式，但导出原 HTML 仍有样式。
 */
function injectIntoDocument(raw: string, headSnippet: string, bodySnippet: string): string {
  const fallback = '<div style="padding:40px;color:#999;font-family:sans-serif">暂无原型内容</div>'
  let html = (raw || '').trim() || fallback
  const lower = html.toLowerCase()

  // 已是完整文档：写入 head / body，保留模型自带 CSS
  const headClose = lower.indexOf('</head>')
  if (headClose >= 0) {
    html = html.slice(0, headClose) + headSnippet + html.slice(headClose)
  } else {
    const bodyOpen = lower.indexOf('<body')
    if (bodyOpen >= 0) {
      const bodyTagEnd = html.indexOf('>', bodyOpen)
      if (bodyTagEnd >= 0) {
        html = html.slice(0, bodyTagEnd + 1) + headSnippet + html.slice(bodyTagEnd + 1)
      } else {
        html = headSnippet + html
      }
    } else if (lower.includes('<html')) {
      html = headSnippet + html
    } else {
      // 片段：包成最小文档，避免 srcdoc 解析异常
      html = `<!DOCTYPE html><html><head><meta charset="UTF-8">${headSnippet}</head><body>${html}</body></html>`
    }
  }

  const lower2 = html.toLowerCase()
  const bodyClose = lower2.lastIndexOf('</body>')
  if (bodyClose >= 0) {
    html = html.slice(0, bodyClose) + bodySnippet + html.slice(bodyClose)
  } else {
    html += bodySnippet
  }
  return html
}

function buildSrcdoc(raw: string, pageMeta?: { titles: string[]; index: number }) {
  // 多页时注入页间跳转桥：把 a/button 的跳转意图交给父页面切 Tab
  const navScript = pageMeta && pageMeta.titles.length > 1 ? buildMultiPageNavScript(pageMeta.titles, pageMeta.index) : ''
  return injectIntoDocument(raw, RUNTIME_STYLE, navScript + RUNTIME_SCRIPT)
}

function buildMultiPageNavScript(titles: string[], index: number): string {
  const safeTitles = JSON.stringify(titles)
  return `<script data-proto-runtime>
(function(){
  var titles=${safeTitles};
  var current=${Math.max(0, index)};
  function go(i){
    if(i==null || i<0 || i>=titles.length || i===current) return;
    parent.postMessage({source:"proto",type:"gotoPage",payload:{index:i,title:titles[i]}}, "*");
  }
  function matchTitle(text){
    if(!text) return -1;
    var t=String(text).trim();
    for(var i=0;i<titles.length;i++){
      if(!titles[i]) continue;
      if(t===titles[i] || t.indexOf(titles[i])>=0 || titles[i].indexOf(t)>=0) return i;
    }
    // 常见：首页/返回/登录 等模糊匹配
    for(var j=0;j<titles.length;j++){
      var name=titles[j]||"";
      if(/首页|主页|home/i.test(t) && /首页|主页|home/i.test(name)) return j;
      if(/登录|登陆|login/i.test(t) && /登录|登陆|login/i.test(name)) return j;
      if(/我的|个人|设置|profile|mine/i.test(t) && /我的|个人|设置|profile|mine/i.test(name)) return j;
      if(/详情|detail/i.test(t) && /详情|detail/i.test(name)) return j;
    }
    return -1;
  }
  document.addEventListener("click", function(e){
    var el=e.target;
    while(el && el!==document && el!==document.body){
      if(el.getAttribute && el.getAttribute("data-proto-page")!=null){
        e.preventDefault(); e.stopPropagation();
        var n=parseInt(el.getAttribute("data-proto-page"),10);
        if(!isNaN(n)) go(n-1>=0 && n-1<titles.length ? n-1 : n);
        return;
      }
      var href=el.getAttribute && el.getAttribute("href");
      if(href){
        // #page-2 / #2 / page-2.html / 页面标题
        var m=href.match(/#?page[-_]?(\\d+)/i) || href.match(/^#(\\d+)$/);
        if(m){ e.preventDefault(); e.stopPropagation(); go(parseInt(m[1],10)-1); return; }
        if(href.charAt(0)==="#" && href.length>1){
          var idx=matchTitle(decodeURIComponent(href.slice(1)));
          if(idx>=0){ e.preventDefault(); e.stopPropagation(); go(idx); return; }
        }
        var file=href.replace(/^.*\\//,"").replace(/\\.html?$/i,"");
        var byFile=matchTitle(file);
        if(byFile>=0){ e.preventDefault(); e.stopPropagation(); go(byFile); return; }
      }
      var label=(el.innerText||el.textContent||"").trim();
      if((el.tagName==="BUTTON" || el.getAttribute("role")==="button" || el.tagName==="A") && label){
        var bi=matchTitle(label);
        // 仅在交互模式下由父页面放开；选择模式仍由运行时接管
        if(bi>=0 && bi!==current && window.__protoMode==="interact"){
          e.preventDefault(); e.stopPropagation(); go(bi); return;
        }
      }
      el=el.parentNode;
    }
  }, true);
  window.__protoMode="select";
  window.addEventListener("message", function(e){
    var d=e.data; if(!d||d.source!=="proto-parent") return;
    if(d.type==="mode") window.__protoMode=d.value;
  });
})();
<\/script>`
}

/* ==================== 状态 ==================== */
const iframeRef = ref<HTMLIFrameElement>()
const previewWrap = ref<HTMLElement>()
const renderHtml = ref('')                 // 当前真正加载进 iframe 的原始 HTML
const frameKey = ref(0)                     // 刷新用：变化即重挂载 iframe
const device = ref<'mobile' | 'tablet' | 'desktop'>(
  props.platform === 'APP' || props.platform === 'MINI_PROGRAM' ? 'mobile'
    : props.platform === 'PAD' ? 'tablet' : 'desktop',
)
const editMode = ref<'select' | 'interact'>('select')
const selected = ref<any | null>(null)
const treeData = ref<any[]>([])
const aiText = ref('')
const aiLoading = ref(false)
const aiSummary = ref('')          // 最近一次 AI 修改说明（成功提示横幅）

let pendingSync: string | null = null       // 标记「由内部编辑回传触发」的 html 变更，避免重挂载
let refreshPending = false
let exportPending = false
let syncTimer: ReturnType<typeof setTimeout> | null = null

const devices = [
  { key: 'mobile', label: '手机', icon: Iphone, width: 390 },
  { key: 'tablet', label: '平板', icon: Platform, width: 820 },
  { key: 'desktop', label: '桌面', icon: Monitor, width: 0 },
] as const

const FONT_WEIGHTS = [
  { label: '常规', value: '400' },
  { label: '中等', value: '500' },
  { label: '加粗', value: '700' },
]
const SHADOWS: Record<string, string> = {
  无: 'none',
  轻: '0 1px 3px rgba(0,0,0,.1)',
  中: '0 4px 12px rgba(0,0,0,.12)',
  重: '0 12px 30px rgba(0,0,0,.18)',
}

const form = reactive({
  text: '', editableText: false, href: '', isLink: false,
  color: '#000000', backgroundColor: '',
  fontSize: 14, fontWeight: '400', textAlign: 'left',
  padding: 0, margin: 0, borderRadius: 0, shadow: '无',
})

const srcdoc = computed(() => buildSrcdoc(renderHtml.value, {
  titles: props.pageTitles || [],
  index: props.pageIndex || 0,
}))
const frameWidth = computed(() => {
  const d = devices.find(x => x.key === device.value)
  return d && d.width ? d.width + 'px' : '100%'
})

/* ==================== html 同步（内 / 外双向） ==================== */
watch(html, (v) => {
  // 由内部编辑回传的变更：DOM 已实时更新，无需重挂载 iframe
  if (v != null && v === pendingSync) { pendingSync = null; return }
  renderHtml.value = v ?? ''
}, { immediate: true })

/* ==================== iframe 通信 ==================== */
function post(msg: Record<string, unknown>) {
  iframeRef.value?.contentWindow?.postMessage({ source: 'proto-parent', ...msg }, '*')
}

function onMessage(e: MessageEvent) {
  if (e.source !== iframeRef.value?.contentWindow) return
  const d = e.data
  if (!d || d.source !== 'proto') return

  if (d.type === 'ready') {
    post({ type: 'mode', value: editMode.value })
    post({ type: 'requestTree' })
  } else if (d.type === 'select') {
    applySelect(d.payload)
  } else if (d.type === 'tree') {
    treeData.value = d.payload
  } else if (d.type === 'html') {
    if (refreshPending) {
      refreshPending = false
      pendingSync = d.payload
      html.value = d.payload
      renderHtml.value = d.payload
      frameKey.value++            // 强制重挂载：以最新内容重置交互状态
      return
    }
    pendingSync = d.payload
    html.value = d.payload
    if (exportPending) { exportPending = false; download(d.payload) }
  } else if (d.type === 'gotoPage') {
    const idx = Number(d.payload?.index)
    if (!Number.isNaN(idx) && idx >= 0) emit('gotoPage', idx)
  }
}

function applySelect(p: any) {
  selected.value = p
  Object.assign(form, {
    text: p.text ?? '',
    editableText: p.text !== null,
    href: p.href,
    isLink: p.isLink,
    color: p.styles.color === 'transparent' ? '#000000' : p.styles.color,
    backgroundColor: p.styles.backgroundColor === 'transparent' ? '' : p.styles.backgroundColor,
    fontSize: p.styles.fontSize,
    fontWeight: FONT_WEIGHTS.some(w => w.value === p.styles.fontWeight) ? p.styles.fontWeight : '400',
    textAlign: p.styles.textAlign,
    padding: p.styles.padding,
    margin: p.styles.margin,
    borderRadius: p.styles.borderRadius,
    shadow: p.styles.boxShadow === 'on' ? '中' : '无',
  })
}

/* 属性推送 + 防抖回传完整 HTML */
function scheduleSync() {
  if (syncTimer) clearTimeout(syncTimer)
  syncTimer = setTimeout(() => post({ type: 'requestHtml' }), 500)
}
function pushStyle(prop: string, value: string | number) {
  if (!selected.value) return
  post({ type: 'style', id: selected.value.id, prop, value })
  scheduleSync()
}
function pushText() {
  if (!selected.value) return
  post({ type: 'text', id: selected.value.id, value: form.text })
  scheduleSync()
}
function pushHref() {
  if (!selected.value) return
  post({ type: 'attr', id: selected.value.id, name: 'href', value: form.href })
  scheduleSync()
}

/* ==================== 工具栏动作 ==================== */
function setDevice(k: 'mobile' | 'tablet' | 'desktop') { device.value = k }

function setMode(m: 'select' | 'interact') {
  editMode.value = m
  post({ type: 'mode', value: m })
  if (m === 'interact') { selected.value = null; post({ type: 'deselect' }) }
}

function refresh() { refreshPending = true; post({ type: 'requestHtml' }) }

function toggleFullscreen() {
  const el = previewWrap.value
  if (!el) return
  if (document.fullscreenElement) document.exitFullscreen()
  else el.requestFullscreen?.()
}

function regenerate() { emit('regenerate') }

function exportHtml() {
  if (!renderHtml.value) { ElMessage.warning('暂无可导出的原型'); return }
  exportPending = true
  post({ type: 'requestHtml' })
}
function download(content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/html' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `原型_${Date.now()}.html`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出 HTML')
  emit('export', content)
}

/* ==================== AI 辅助修改 ==================== */
async function doAiModify() {
  const text = aiText.value.trim()
  if (!text) { ElMessage.warning('请先描述你想修改的内容'); return }
  if (!props.prototypeId) { ElMessage.error('缺少原型 ID，无法调用 AI 修改'); return }
  if (aiLoading.value) return

  aiLoading.value = true
  aiSummary.value = ''
  try {
    const res = await aiEditPrototype(props.prototypeId, {
      instruction: text,
      currentHtml: html.value,          // 传当前最新 HTML（含用户手动微调）
    })
    const data = res.data.data
    html.value = data.newHtml           // 更新 v-model → 上方 iframe 自动刷新
    aiSummary.value = data.changeSummary || 'AI 已根据你的描述完成修改'
    aiText.value = ''
    selected.value = null
    ElMessage.success('AI 修改完成')
  } catch {
    // 失败提示由 axios 响应拦截器统一处理
  } finally {
    aiLoading.value = false
  }
}

/* ==================== 结构树 ==================== */
function onTreeClick(data: any) {
  if (editMode.value !== 'select') setMode('select')
  post({ type: 'selectById', id: data.id })
}
function onTreeDrop(dragNode: any, dropNode: any, type: 'before' | 'after' | 'inner') {
  post({
    type: 'move',
    id: dragNode.data.id,
    targetId: dropNode.data.id,
    position: type === 'inner' ? 'inner' : type,
  })
}

/* ==================== iframe 生命周期 ==================== */
function onFrameLoad() {
  selected.value = null
  treeData.value = []
}

onMounted(() => window.addEventListener('message', onMessage))
onBeforeUnmount(() => {
  window.removeEventListener('message', onMessage)
  if (syncTimer) clearTimeout(syncTimer)
})
</script>

<template>
  <div class="result-right">
    <!-- ==================== 上方：实时预览区 (65%) ==================== -->
    <section class="preview-zone">
      <!-- 精致工具栏 -->
      <header class="tool-bar">
        <div class="tool-group devices">
          <button
            v-for="d in devices" :key="d.key"
            class="tool-btn" :class="{ active: device === d.key }"
            :title="d.label" @click="setDevice(d.key)"
          >
            <el-icon><component :is="d.icon" /></el-icon>
          </button>
        </div>

        <div class="tool-divider" />

        <div class="tool-group">
          <button class="tool-btn" title="刷新预览" @click="refresh">
            <el-icon><RefreshRight /></el-icon>
          </button>
          <button class="tool-btn" title="全屏" @click="toggleFullscreen">
            <el-icon><FullScreen /></el-icon>
          </button>
        </div>

        <div class="tool-spacer" />

        <!-- 选择 / 交互 模式 -->
        <div class="mode-switch">
          <button class="mode-item" :class="{ active: editMode === 'select' }" @click="setMode('select')">
            <el-icon><Pointer /></el-icon><span>选择</span>
          </button>
          <button class="mode-item" :class="{ active: editMode === 'interact' }" @click="setMode('interact')">
            <el-icon><View /></el-icon><span>交互</span>
          </button>
        </div>

        <el-button class="bar-action" size="small" :loading="regenerating" @click="regenerate">
          <el-icon><MagicStick /></el-icon><span>重新生成</span>
        </el-button>
        <el-button class="bar-action primary" size="small" @click="exportHtml">
          <el-icon><Download /></el-icon><span>导出 HTML</span>
        </el-button>
      </header>

      <!-- 预览画布 -->
      <div ref="previewWrap" class="preview-canvas" :class="{ framed: device !== 'desktop' }">
        <div class="frame-holder" :style="{ width: frameWidth }">
          <iframe
            :key="frameKey"
            ref="iframeRef"
            :srcdoc="srcdoc"
            sandbox="allow-scripts allow-same-origin allow-forms"
            class="preview-frame"
            title="原型预览"
            @load="onFrameLoad"
          />
        </div>

        <div v-if="regenerating || aiLoading" class="canvas-mask">
          <el-icon class="spin" size="28"><MagicStick /></el-icon>
          <p>{{ regenerating ? 'AI 正在生成原型…' : 'AI 正在按你的描述修改…' }}</p>
        </div>
      </div>
    </section>

    <!-- ==================== 下方：可视化编辑面板 (35%) ==================== -->
    <section class="edit-zone">
      <!-- AI 辅助修改（核心） -->
      <div class="ai-bar">
        <div class="ai-icon"><el-icon><MagicStick /></el-icon></div>
        <el-input
          v-model="aiText"
          class="ai-input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 3 }"
          resize="none"
          :disabled="aiLoading"
          placeholder="描述你想修改的地方，例如：把登录按钮改成蓝色、标题加大一号、在页面顶部加返回按钮"
          @keydown.enter.exact.prevent="doAiModify"
        />
        <el-button class="ai-btn" type="primary" :loading="aiLoading" @click="doAiModify">
          <el-icon v-if="!aiLoading"><MagicStick /></el-icon>
          {{ aiLoading ? '修改中…' : '让 AI 修改' }}
        </el-button>
      </div>

      <!-- AI 修改成功说明横幅 -->
      <transition name="summary-fade">
        <div v-if="aiSummary" class="ai-summary">
          <el-icon class="summary-ico"><CircleCheck /></el-icon>
          <span class="summary-text">{{ aiSummary }}</span>
          <el-icon class="summary-close" title="关闭" @click="aiSummary = ''"><Close /></el-icon>
        </div>
      </transition>

      <div class="edit-body">
        <!-- 结构树（左窄栏） -->
        <aside class="tree-col">
          <div class="col-title"><el-icon><Rank /></el-icon><span>页面结构</span></div>
          <el-scrollbar class="tree-scroll">
            <el-tree
              v-if="treeData.length"
              :data="treeData"
              node-key="id"
              draggable
              :expand-on-click-node="false"
              :default-expand-all="false"
              :props="{ label: 'label', children: 'children' }"
              @node-click="onTreeClick"
              @node-drop="onTreeDrop"
            />
            <p v-else class="tree-empty">加载中…</p>
          </el-scrollbar>
        </aside>

        <!-- 属性面板 -->
        <div class="prop-col">
          <div class="col-title"><el-icon><Brush /></el-icon><span>属性编辑</span></div>

          <el-scrollbar v-if="selected" class="prop-scroll">
            <div class="prop-inner">
              <div class="sel-chip">
                &lt;{{ selected.tag }}&gt;
                <el-icon class="chip-close" @click="setMode('interact')"><Close /></el-icon>
              </div>

              <!-- 文本内容 -->
              <div v-if="form.editableText" class="field">
                <label>文字内容</label>
                <el-input v-model="form.text" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" @input="pushText" />
              </div>

              <!-- 按钮 / 链接 -->
              <div v-if="form.isLink" class="field">
                <label>链接地址</label>
                <el-input v-model="form.href" placeholder="https:// 或 #" @input="pushHref" />
              </div>

              <!-- 颜色 -->
              <div class="field-row">
                <div class="field">
                  <label>文字颜色</label>
                  <el-color-picker v-model="form.color" @change="pushStyle('color', form.color)" />
                </div>
                <div class="field">
                  <label>背景颜色</label>
                  <el-color-picker v-model="form.backgroundColor" show-alpha @change="pushStyle('backgroundColor', form.backgroundColor || 'transparent')" />
                </div>
              </div>

              <!-- 字体 -->
              <div class="field">
                <label>字体大小 <span class="val">{{ form.fontSize }}px</span></label>
                <el-slider v-model="form.fontSize" :min="8" :max="72" @input="pushStyle('fontSize', form.fontSize)" />
              </div>
              <div class="field-row">
                <div class="field">
                  <label>字重</label>
                  <el-select v-model="form.fontWeight" size="small" @change="pushStyle('fontWeight', form.fontWeight)">
                    <el-option v-for="w in FONT_WEIGHTS" :key="w.value" :label="w.label" :value="w.value" />
                  </el-select>
                </div>
                <div class="field">
                  <label>对齐</label>
                  <el-radio-group v-model="form.textAlign" size="small" @change="pushStyle('textAlign', form.textAlign)">
                    <el-radio-button value="left">左</el-radio-button>
                    <el-radio-button value="center">中</el-radio-button>
                    <el-radio-button value="right">右</el-radio-button>
                  </el-radio-group>
                </div>
              </div>

              <!-- 间距 -->
              <div class="field">
                <label>内边距 <span class="val">{{ form.padding }}px</span></label>
                <el-slider v-model="form.padding" :min="0" :max="80" @input="pushStyle('padding', form.padding)" />
              </div>
              <div class="field">
                <label>外边距 <span class="val">{{ form.margin }}px</span></label>
                <el-slider v-model="form.margin" :min="0" :max="80" @input="pushStyle('margin', form.margin)" />
              </div>

              <!-- 圆角 / 阴影 -->
              <div class="field">
                <label>圆角 <span class="val">{{ form.borderRadius }}px</span></label>
                <el-slider v-model="form.borderRadius" :min="0" :max="40" @input="pushStyle('borderRadius', form.borderRadius)" />
              </div>
              <div class="field">
                <label>阴影</label>
                <el-radio-group v-model="form.shadow" size="small" @change="pushStyle('boxShadow', SHADOWS[form.shadow])">
                  <el-radio-button v-for="(_, k) in SHADOWS" :key="k" :value="k">{{ k }}</el-radio-button>
                </el-radio-group>
              </div>
            </div>
          </el-scrollbar>

          <div v-else class="prop-empty">
            <el-icon size="26"><Pointer /></el-icon>
            <p>在上方预览中<b>点击任意元素</b>，即可编辑它的文字与样式</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.result-right {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f7f7f4;
  overflow: hidden;
}

/* ============ 预览区 65% ============ */
.preview-zone {
  flex: 0 0 65%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid rgba(38, 37, 30, 0.1);
}

.tool-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fff;
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
  flex-shrink: 0;
}
.tool-group { display: flex; gap: 2px; }
.tool-divider { width: 1px; height: 18px; background: rgba(38, 37, 30, 0.12); margin: 0 2px; }
.tool-spacer { flex: 1; }

.tool-btn {
  width: 30px; height: 30px;
  display: flex; align-items: center; justify-content: center;
  border: none; background: transparent; border-radius: 7px;
  color: rgba(38, 37, 30, 0.55); cursor: pointer; transition: all .15s;
  font-size: 15px;
}
.tool-btn:hover { background: #f2f1ed; color: #26251e; }
.tool-btn.active { background: #26251e; color: #f2f1ed; }

.mode-switch {
  display: flex; gap: 2px; padding: 2px;
  background: #f2f1ed; border-radius: 8px;
}
.mode-item {
  display: flex; align-items: center; gap: 4px;
  padding: 4px 10px; border: none; background: transparent;
  border-radius: 6px; font-size: 12px; cursor: pointer;
  color: rgba(38, 37, 30, 0.55); transition: all .15s;
}
.mode-item.active { background: #fff; color: #26251e; box-shadow: 0 1px 2px rgba(0, 0, 0, .06); }

.bar-action {
  border-radius: 7px !important; font-size: 12px;
  background: #f2f1ed !important; border-color: transparent !important; color: #26251e !important;
}
.bar-action span { margin-left: 4px; }
.bar-action.primary { background: #26251e !important; color: #f2f1ed !important; }
.bar-action.primary:hover { opacity: .88; }

.preview-canvas {
  flex: 1; min-height: 0; position: relative;
  display: flex; justify-content: center; align-items: stretch;
  background: #f7f7f4; overflow: auto; padding: 0;
}
.preview-canvas.framed { padding: 20px; align-items: flex-start; background: #ecebe6; }
.frame-holder {
  height: 100%; background: #fff; transition: width .25s ease;
  max-width: 100%;
}
.preview-canvas.framed .frame-holder {
  height: auto; min-height: calc(100% - 0px);
  border-radius: 14px; overflow: hidden;
  box-shadow: 0 10px 40px rgba(38, 37, 30, .15);
  border: 1px solid rgba(38, 37, 30, 0.08);
  align-self: stretch;
}
.preview-frame { width: 100%; height: 100%; border: none; display: block; background: #fff; }

.canvas-mask {
  position: absolute; inset: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px;
  background: rgba(247, 247, 244, 0.82); backdrop-filter: blur(2px);
  color: rgba(38, 37, 30, 0.6); font-size: 13px;
}
.spin { animation: spin 1.1s linear infinite; color: #f54e00; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ============ 编辑区 35% ============ */
.edit-zone {
  flex: 1; min-height: 0;
  display: flex; flex-direction: column;
  background: #fff;
}

.ai-bar {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 10px 12px;
  background: linear-gradient(180deg, rgba(245, 78, 0, 0.05), rgba(245, 78, 0, 0.02));
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
  flex-shrink: 0;
}
.ai-icon {
  width: 30px; height: 30px; flex-shrink: 0; margin-top: 2px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 8px; background: #f54e00; color: #fff; font-size: 15px;
}
.ai-input { flex: 1; }
.ai-input :deep(.el-textarea__inner) {
  border-radius: 8px; font-size: 13px; line-height: 1.5;
  box-shadow: 0 0 0 1px rgba(38, 37, 30, 0.12) inset;
}
.ai-btn { flex-shrink: 0; border-radius: 8px !important; background: #f54e00 !important; border-color: #f54e00 !important; }

/* AI 修改成功说明横幅 */
.ai-summary {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; margin: 0;
  background: rgba(103, 194, 58, 0.1);
  border-bottom: 1px solid rgba(103, 194, 58, 0.2);
  color: #4a9e28; font-size: 12px; line-height: 1.5;
  flex-shrink: 0;
}
.summary-ico { font-size: 15px; flex-shrink: 0; }
.summary-text { flex: 1; }
.summary-close { cursor: pointer; border-radius: 4px; color: rgba(38, 37, 30, 0.4); }
.summary-close:hover { color: #4a9e28; background: rgba(103, 194, 58, 0.15); }
.summary-fade-enter-active, .summary-fade-leave-active { transition: opacity .2s, transform .2s; }
.summary-fade-enter-from, .summary-fade-leave-to { opacity: 0; transform: translateY(-4px); }

.edit-body { flex: 1; min-height: 0; display: flex; }

.col-title {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 12px 8px; font-size: 12px; font-weight: 600;
  color: rgba(38, 37, 30, 0.55); letter-spacing: .3px;
}

/* 结构树 */
.tree-col {
  width: 180px; flex-shrink: 0;
  border-right: 1px solid rgba(38, 37, 30, 0.08);
  display: flex; flex-direction: column; min-height: 0;
  background: #fbfbf9;
}
.tree-scroll { flex: 1; min-height: 0; }
.tree-empty { padding: 12px; font-size: 12px; color: rgba(38, 37, 30, 0.35); }
:deep(.el-tree) { background: transparent; font-size: 12px; padding: 0 6px 12px; }
:deep(.el-tree-node__content) { height: 28px; border-radius: 6px; }
:deep(.el-tree-node__content:hover) { background: #f2f1ed; }

/* 属性面板 */
.prop-col { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
.prop-scroll { flex: 1; min-height: 0; }
.prop-inner { padding: 0 14px 20px; display: flex; flex-direction: column; gap: 14px; }

.sel-chip {
  display: inline-flex; align-items: center; gap: 8px; align-self: flex-start;
  padding: 3px 6px 3px 10px; border-radius: 6px;
  background: rgba(245, 78, 0, 0.1); color: #f54e00;
  font-family: ui-monospace, monospace; font-size: 12px; font-weight: 600;
}
.chip-close { cursor: pointer; border-radius: 4px; }
.chip-close:hover { background: rgba(245, 78, 0, 0.2); }

.field { display: flex; flex-direction: column; gap: 6px; }
.field-row { display: flex; gap: 16px; }
.field-row .field { flex: 1; }
.field > label {
  font-size: 12px; color: rgba(38, 37, 30, 0.6); font-weight: 500;
  display: flex; justify-content: space-between; align-items: center;
}
.field .val { color: #f54e00; font-weight: 600; }

.prop-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; padding: 20px; text-align: center;
  color: rgba(38, 37, 30, 0.4); font-size: 12px; line-height: 1.7;
}
.prop-empty b { color: rgba(38, 37, 30, 0.7); }

:deep(.el-slider) { --el-slider-height: 4px; }
:deep(.el-slider__button) { width: 14px; height: 14px; }
:deep(.el-color-picker) { --el-color-picker-size: 30px; }
</style>
