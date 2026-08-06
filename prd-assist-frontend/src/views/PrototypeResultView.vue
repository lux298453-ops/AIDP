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
import { aiEditPrototype, getPrototype, savePrototypeAiEditSnapshot, startPrototypeAiEditStream } from '@/api/prototype'
import { getTaskSseUrl } from '@/api/task'

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

type PrototypePatchOp =
  | 'setStyle'
  | 'setText'
  | 'setAttr'
  | 'addClass'
  | 'removeClass'
  | 'insertHtml'
  | 'replaceHtml'
  | 'remove'
  | 'summary'

interface PrototypePatch {
  seq?: number
  op: PrototypePatchOp
  selector?: string
  all?: boolean
  property?: string
  name?: string
  value?: string
  html?: string
  text?: string
  position?: InsertPosition
  summary?: string
}

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
  function isTextTag(el){ return /^(a|button|label|span|strong|em|small|p|h1|h2|h3|h4|h5|h6|li|td|th|dt|dd|figcaption|summary)$/i.test(el.tagName||""); }
  function directTextNode(el){ for(var i=0;i<el.childNodes.length;i++){ var n=el.childNodes[i]; if(n.nodeType===3 && String(n.nodeValue||"").trim()) return n; } return null; }
  function textLeaf(el){
    if(!el||!el.querySelectorAll) return null;
    var tags=el.querySelectorAll("a,button,label,span,strong,em,small,p,h1,h2,h3,h4,h5,h6,li,td,th,dt,dd,figcaption,summary");
    var hits=[];
    for(var i=0;i<tags.length;i++){
      if(tags[i].querySelector("a,button,label,span,strong,em,small,p,h1,h2,h3,h4,h5,h6,li,td,th,dt,dd,figcaption,summary")) continue;
      if(String(tags[i].textContent||"").trim()) hits.push(tags[i]);
    }
    return hits.length===1?hits[0]:null;
  }
  function editableText(el){
    if(!el || el===document.body || el===document.documentElement) return null;
    if(directTextNode(el)) return (el.textContent||"").trim();
    if(!el.children || el.children.length===0) return el.textContent;
    if(isTextTag(el)) return (el.textContent||"").trim();
    var leaf=textLeaf(el);
    return leaf ? (leaf.textContent||"").trim() : null;
  }
  function setElementText(el,value){
    if(!el) return;
    var text=value==null?"":String(value);
    var direct=directTextNode(el);
    if(direct){ direct.nodeValue=text; return; }
    if(!el.children || el.children.length===0 || isTextTag(el)){ el.textContent=text; return; }
    var leaf=textLeaf(el);
    if(leaf){ leaf.textContent=text; return; }
    el.textContent=text;
  }
  function normWeight(w){ if(w==="normal") return "400"; if(w==="bold") return "700"; return String(w); }
  function num(v){ var n=parseFloat(v); return isNaN(n)?0:Math.round(n); }
  function pct(v){ var n=parseFloat(v); return isNaN(n)?100:Math.round(n*100); }
  function cssName(prop){ return String(prop||"").replace(/[A-Z]/g,function(m){return "-"+m.toLowerCase();}); }
  function info(el){ var cs=getComputedStyle(el); var tag=el.tagName.toLowerCase();
    return { id:pid(el), tag:tag, text:editableText(el), href:el.getAttribute("href")||"",
      isLink: el.hasAttribute("href")||tag==="button"||el.getAttribute("role")==="button",
      styles:{ color:toHex(cs.color), backgroundColor:toHex(cs.backgroundColor),
        fontSize:parseInt(cs.fontSize)||14, fontWeight:normWeight(cs.fontWeight), textAlign:cs.textAlign||"left",
        fontFamily:(cs.fontFamily||"").split(",")[0].replace(/["']/g,""), lineHeight:num(cs.lineHeight), letterSpacing:num(cs.letterSpacing),
        fontStyle:cs.fontStyle||"normal", textDecoration:cs.textDecorationLine||"none",
        paddingTop:num(cs.paddingTop), paddingRight:num(cs.paddingRight), paddingBottom:num(cs.paddingBottom), paddingLeft:num(cs.paddingLeft),
        marginTop:num(cs.marginTop), marginRight:num(cs.marginRight), marginBottom:num(cs.marginBottom), marginLeft:num(cs.marginLeft),
        width:num(cs.width), height:num(cs.height), flexBasis:cs.flexBasis==="auto"?0:num(cs.flexBasis),
        minWidth:num(cs.minWidth), maxWidth:cs.maxWidth==="none"?0:num(cs.maxWidth),
        minHeight:num(cs.minHeight), maxHeight:cs.maxHeight==="none"?0:num(cs.maxHeight),
        display:cs.display||"block", flexDirection:cs.flexDirection||"row", justifyContent:cs.justifyContent||"flex-start",
        alignItems:cs.alignItems||"stretch", gap:num(cs.gap), position:cs.position||"static",
        top:cs.top==="auto"?0:num(cs.top), right:cs.right==="auto"?0:num(cs.right), bottom:cs.bottom==="auto"?0:num(cs.bottom), left:cs.left==="auto"?0:num(cs.left),
        zIndex:cs.zIndex==="auto"?0:parseInt(cs.zIndex)||0,
        borderRadius:num(cs.borderTopLeftRadius), borderWidth:num(cs.borderTopWidth), borderStyle:cs.borderTopStyle||"none",
        borderColor:toHex(cs.borderTopColor), opacity:pct(cs.opacity),
        boxShadow:(cs.boxShadow&&cs.boxShadow!=="none")?"on":"off" } };
  }
  function select(el){ if(selected) selected.removeAttribute("data-proto-selected"); selected=el; el.setAttribute("data-proto-selected",""); post("select", info(el)); }
  document.addEventListener("mouseover", function(e){ if(mode!=="select") return; var el=e.target; if(el===document.body||el===document.documentElement||!el.setAttribute) return; el.setAttribute("data-proto-hover",""); }, true);
  document.addEventListener("mouseout", function(e){ if(e.target&&e.target.removeAttribute) e.target.removeAttribute("data-proto-hover"); }, true);
  document.addEventListener("click", function(e){ if(mode!=="select") return; e.preventDefault(); e.stopPropagation(); var el=e.target; if(!el||el===document.body||el===document.documentElement) return; select(el); }, true);
  function splitTracks(value){ var out=[],cur="",depth=0; for(var i=0;i<value.length;i++){ var ch=value.charAt(i); if(ch==="(") depth++; if(ch===")") depth=Math.max(0,depth-1); if(/\\s/.test(ch)&&depth===0){ if(cur){ out.push(cur); cur=""; } } else { cur+=ch; } } if(cur) out.push(cur); return out; }
  function applySmartWidth(id,val){ var el=byId(id); if(!el) return; var n=parseFloat(val); if(isNaN(n)) n=0; var px=Math.max(0,n)+"px";
    el.style.removeProperty("flex");
    el.style.setProperty("width",px,"important"); el.style.setProperty("flex-basis",px,"important");
    el.style.setProperty("min-width",px,"important"); el.style.setProperty("max-width",px,"important");
    el.style.setProperty("flex-grow","0","important"); el.style.setProperty("flex-shrink","0","important");
    if(n===0){ el.style.setProperty("overflow","hidden","important"); el.style.setProperty("padding-left","0px","important"); el.style.setProperty("padding-right","0px","important"); el.style.setProperty("margin-left","0px","important"); el.style.setProperty("margin-right","0px","important"); }
    var parent=el.parentElement; if(parent){ var ps=getComputedStyle(parent); var children=Array.prototype.slice.call(parent.children).filter(function(c){ return !c.hasAttribute("data-proto-runtime"); }); var idx=children.indexOf(el);
      if(idx>=0&&ps.display.indexOf("grid")>=0){ var tracks=splitTracks(ps.gridTemplateColumns||""); if(tracks.length>idx){ tracks[idx]=px; parent.style.setProperty("grid-template-columns",tracks.join(" "),"important"); } }
      if(idx>=0&&ps.display.indexOf("flex")>=0){ el.style.setProperty("flex","0 0 "+px,"important"); }
    } }
  function applyStyle(id,prop,val){ var el=byId(id); if(!el) return;
    var value=val==null?"":String(val);
    if(prop==="smartWidth"){ applySmartWidth(id,value); return; }
    if(value===""){ el.style.removeProperty(cssName(prop)); return; }
    if(prop==="flexBasis"){ el.style.removeProperty("flex"); }
    el.style.setProperty(cssName(prop), value, "important"); }
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
  function targets(selector, all){ if(!selector) return []; try{
      var list=Array.prototype.slice.call(document.querySelectorAll(selector));
      list=list.filter(function(el){ return el&&el.setAttribute&&!el.hasAttribute("data-proto-runtime"); });
      return all?list:(list.length?[list[0]]:[]);
    }catch(err){ return []; } }
  function replaceElement(el,html){ if(!el||!el.parentNode) return; var t=document.createElement("template"); t.innerHTML=html||""; var nodes=Array.prototype.slice.call(t.content.childNodes); if(!nodes.length) return; for(var i=0;i<nodes.length;i++){ el.parentNode.insertBefore(nodes[i],el); } el.parentNode.removeChild(el); }
  function applyOnePatch(el,p){
    if(p.op==="setStyle"){ el.style.setProperty(cssName(p.property), p.value==null?"":String(p.value)); }
    else if(p.op==="setText"){ el.textContent=p.value==null?"":String(p.value); }
    else if(p.op==="setAttr"){ if(p.name){ el.setAttribute(String(p.name), p.value==null?"":String(p.value)); } }
    else if(p.op==="addClass"){ String(p.value||"").split(/\\s+/).filter(Boolean).forEach(function(c){el.classList.add(c);}); }
    else if(p.op==="removeClass"){ String(p.value||"").split(/\\s+/).filter(Boolean).forEach(function(c){el.classList.remove(c);}); }
    else if(p.op==="insertHtml"){ var pos=/^(beforebegin|afterbegin|beforeend|afterend)$/.test(p.position||"")?p.position:"beforeend"; el.insertAdjacentHTML(pos,p.html||""); }
    else if(p.op==="replaceHtml"){ replaceElement(el,p.html||""); }
    else if(p.op==="remove"){ if(el.parentNode) el.parentNode.removeChild(el); }
  }
  function finishPatch(p,matched){ post("patchApplied",{seq:p.seq,summary:p.summary||"",matched:matched}); post("tree",tree()); post("html",clean()); }
  function applyPatch(p){ if(!p||!p.op) return; if(p.op==="summary"){ post("patchApplied",{seq:p.seq,summary:p.text||p.summary||"",matched:0}); return; }
    var els=targets(p.selector,!!p.all), matched=els.length;
    if(p.all&&els.length>1){
      els.forEach(function(el,i){ window.setTimeout(function(){ applyOnePatch(el,p); if(i===els.length-1) finishPatch(p,matched); }, i*90); });
      return;
    }
    for(var i=0;i<els.length;i++){ applyOnePatch(els[i],p); }
    finishPatch(p,matched); }
  window.addEventListener("message", function(e){ var d=e.data; if(!d||d.source!=="proto-parent") return;
    if(d.type==="style"){ applyStyle(d.id,d.prop,d.value); }
    else if(d.type==="text"){ var e1=byId(d.id); if(e1){ setElementText(e1,d.value); post("select", info(e1)); post("tree",tree()); post("html",clean()); } }
    else if(d.type==="attr"){ var e2=byId(d.id); if(e2) e2.setAttribute(d.name,d.value); }
    else if(d.type==="mode"){ mode=d.value; if(mode!=="select"){ var hs=document.querySelectorAll("[data-proto-hover]"); for(var i=0;i<hs.length;i++) hs[i].removeAttribute("data-proto-hover"); } }
    else if(d.type==="selectById"){ var e3=byId(d.id); if(e3){ select(e3); e3.scrollIntoView({block:"nearest"}); } }
    else if(d.type==="deselect"){ if(selected){ selected.removeAttribute("data-proto-selected"); selected=null; } }
    else if(d.type==="move"){ move(d.id,d.targetId,d.position); post("tree",tree()); post("html",clean()); }
    else if(d.type==="patch"){ applyPatch(d.patch); }
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
const aiPatchCount = ref(0)
const frameReady = ref(false)

let pendingSync: string | null = null       // 标记「由内部编辑回传触发」的 html 变更，避免重挂载
let refreshPending = false
let exportPending = false
let syncTimer: ReturnType<typeof setTimeout> | null = null
let aiEditEventSource: EventSource | null = null
let queuedPatches: PrototypePatch[] = []
let snapshotResolver: ((value: string) => void) | null = null
let snapshotTimer: ReturnType<typeof setTimeout> | null = null
let lastPatchSeqReceived = 0
let lastPatchSeqApplied = 0
let patchAppliedWaiters: Array<() => void> = []
let streamIdleTimer: ReturnType<typeof setTimeout> | null = null

const devices = [
  { key: 'mobile', label: '手机', icon: Iphone, width: 390 },
  { key: 'tablet', label: '平板', icon: Platform, width: 1024 },
  { key: 'desktop', label: '桌面', icon: Monitor, width: 0 },
] as const

const FONT_WEIGHTS = [
  { label: '常规', value: '400' },
  { label: '中等', value: '500' },
  { label: '加粗', value: '700' },
]
const FONT_FAMILIES = [
  { label: '系统默认', value: '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif' },
  { label: '苹方/雅黑', value: '"PingFang SC","Microsoft YaHei",sans-serif' },
  { label: '宋体', value: 'SimSun,serif' },
  { label: '等宽', value: '"SFMono-Regular",Consolas,monospace' },
]
const DISPLAY_OPTIONS = ['block', 'inline-block', 'flex', 'grid', 'none']
const FLEX_DIRECTIONS = [
  { label: '横向', value: 'row' },
  { label: '纵向', value: 'column' },
]
const JUSTIFY_OPTIONS = [
  { label: '起点', value: 'flex-start' },
  { label: '居中', value: 'center' },
  { label: '终点', value: 'flex-end' },
  { label: '两端', value: 'space-between' },
  { label: '环绕', value: 'space-around' },
]
const ALIGN_OPTIONS = [
  { label: '拉伸', value: 'stretch' },
  { label: '起点', value: 'flex-start' },
  { label: '居中', value: 'center' },
  { label: '终点', value: 'flex-end' },
]
const POSITION_OPTIONS = ['static', 'relative', 'absolute', 'fixed', 'sticky']
const BORDER_STYLES = ['none', 'solid', 'dashed', 'dotted']
const SHADOWS: Record<string, string> = {
  无: 'none',
  轻: '0 1px 3px rgba(0,0,0,.1)',
  中: '0 4px 12px rgba(0,0,0,.12)',
  重: '0 12px 30px rgba(0,0,0,.18)',
}

const activePropTab = ref('text')
const form = reactive({
  text: '', editableText: false, href: '', isLink: false,
  color: '#000000', backgroundColor: '',
  fontSize: 14, fontWeight: '400', textAlign: 'left',
  fontFamily: '', lineHeight: 0, letterSpacing: 0, fontStyle: 'normal', textDecoration: 'none',
  paddingTop: 0, paddingRight: 0, paddingBottom: 0, paddingLeft: 0,
  marginTop: 0, marginRight: 0, marginBottom: 0, marginLeft: 0,
  width: 0, height: 0, flexBasis: 0, minWidth: 0, maxWidth: 0, minHeight: 0, maxHeight: 0,
  display: 'block', flexDirection: 'row', justifyContent: 'flex-start', alignItems: 'stretch', gap: 0,
  position: 'static', top: 0, right: 0, bottom: 0, left: 0, zIndex: 0,
  borderRadius: 0, borderWidth: 0, borderStyle: 'none', borderColor: '#dcdfe6',
  opacity: 100, shadow: '无',
})

const srcdoc = computed(() => buildSrcdoc(renderHtml.value, {
  titles: props.pageTitles || [],
  index: props.pageIndex || 0,
}))
const frameWidth = computed(() => {
  const d = devices.find(x => x.key === device.value)
  return d && d.width ? d.width + 'px' : '100%'
})
const aiLoadingText = computed(() => (
  aiPatchCount.value > 0
    ? `AI 正在修改，已应用 ${aiPatchCount.value} 条指令…`
    : 'AI 正在按你的描述修改…'
))

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

function sendPatchToFrame(patch: PrototypePatch) {
  if (!frameReady.value) {
    queuedPatches.push(patch)
    return
  }
  post({ type: 'patch', patch })
}

function flushQueuedPatches() {
  if (!frameReady.value || !queuedPatches.length) return
  const patches = queuedPatches
  queuedPatches = []
  patches.forEach(sendPatchToFrame)
}

function requestHtmlSnapshot(timeout = 2500): Promise<string> {
  return new Promise((resolve) => {
    if (snapshotTimer) clearTimeout(snapshotTimer)
    snapshotResolver = resolve
    snapshotTimer = setTimeout(() => {
      snapshotResolver = null
      snapshotTimer = null
      resolve(html.value || renderHtml.value || '')
    }, timeout)
    post({ type: 'requestHtml' })
  })
}

function notifyPatchAppliedWaiters() {
  if (lastPatchSeqApplied < lastPatchSeqReceived) return
  const waiters = patchAppliedWaiters
  patchAppliedWaiters = []
  waiters.forEach(resolve => resolve())
}

function waitForPatchesApplied(timeout = 6000): Promise<void> {
  if (lastPatchSeqApplied >= lastPatchSeqReceived) return Promise.resolve()
  return new Promise((resolve) => {
    let timer: number
    const done = () => {
      window.clearTimeout(timer)
      resolve()
    }
    timer = window.setTimeout(() => {
      patchAppliedWaiters = patchAppliedWaiters.filter(item => item !== done)
      resolve()
    }, timeout)
    patchAppliedWaiters.push(done)
  })
}

function persistCurrentPreviewInBackground(summary: string) {
  void requestHtmlSnapshot(4000).then((finalHtml) => {
    if (!finalHtml || !finalHtml.trim()) {
      ElMessage.warning('预览已完成修改，但未能读取最终 HTML，请手动导出或刷新后重试')
      return
    }
    void persistAiEditSnapshot(finalHtml, summary)
  })
}

function onMessage(e: MessageEvent) {
  if (e.source !== iframeRef.value?.contentWindow) return
  const d = e.data
  if (!d || d.source !== 'proto') return

  if (d.type === 'ready') {
    frameReady.value = true
    post({ type: 'mode', value: editMode.value })
    post({ type: 'requestTree' })
    flushQueuedPatches()
  } else if (d.type === 'select') {
    applySelect(d.payload)
  } else if (d.type === 'tree') {
    treeData.value = d.payload
  } else if (d.type === 'html') {
    const payload = d.payload || ''
    if (refreshPending) {
      refreshPending = false
      pendingSync = payload
      html.value = payload
      renderHtml.value = payload
      frameKey.value++            // 强制重挂载：以最新内容重置交互状态
      return
    }
    pendingSync = payload
    html.value = payload
    if (snapshotResolver) {
      const resolve = snapshotResolver
      snapshotResolver = null
      if (snapshotTimer) { clearTimeout(snapshotTimer); snapshotTimer = null }
      resolve(payload)
    }
    if (exportPending) { exportPending = false; download(payload) }
  } else if (d.type === 'gotoPage') {
    const idx = Number(d.payload?.index)
    if (!Number.isNaN(idx) && idx >= 0) emit('gotoPage', idx)
  } else if (d.type === 'patchApplied') {
    const seq = Number(d.payload?.seq)
    if (!Number.isNaN(seq)) {
      lastPatchSeqApplied = Math.max(lastPatchSeqApplied, seq)
      notifyPatchAppliedWaiters()
    }
    const summary = d.payload?.summary
    if (summary) aiSummary.value = summary
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
    fontFamily: p.styles.fontFamily || '',
    lineHeight: p.styles.lineHeight,
    letterSpacing: p.styles.letterSpacing,
    textAlign: p.styles.textAlign,
    fontStyle: p.styles.fontStyle,
    textDecoration: p.styles.textDecoration,
    paddingTop: p.styles.paddingTop,
    paddingRight: p.styles.paddingRight,
    paddingBottom: p.styles.paddingBottom,
    paddingLeft: p.styles.paddingLeft,
    marginTop: p.styles.marginTop,
    marginRight: p.styles.marginRight,
    marginBottom: p.styles.marginBottom,
    marginLeft: p.styles.marginLeft,
    width: p.styles.width,
    height: p.styles.height,
    flexBasis: p.styles.flexBasis,
    minWidth: p.styles.minWidth,
    maxWidth: p.styles.maxWidth,
    minHeight: p.styles.minHeight,
    maxHeight: p.styles.maxHeight,
    display: p.styles.display,
    flexDirection: p.styles.flexDirection,
    justifyContent: p.styles.justifyContent,
    alignItems: p.styles.alignItems,
    gap: p.styles.gap,
    position: p.styles.position,
    top: p.styles.top,
    right: p.styles.right,
    bottom: p.styles.bottom,
    left: p.styles.left,
    zIndex: p.styles.zIndex,
    borderRadius: p.styles.borderRadius,
    borderWidth: p.styles.borderWidth,
    borderStyle: p.styles.borderStyle,
    borderColor: p.styles.borderColor,
    opacity: p.styles.opacity,
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
function pushPxStyle(prop: string, value: number | undefined) {
  if (value === undefined || Number.isNaN(value)) return
  pushStyle(prop, `${value}px`)
}
function pushWidthStyle(value: number | undefined) {
  if (value === undefined || Number.isNaN(value)) return
  form.flexBasis = value
  pushStyle('smartWidth', `${value}px`)
}
function pushNumberStyle(prop: string, value: number | undefined) {
  if (value === undefined || Number.isNaN(value)) return
  pushStyle(prop, String(value))
}
function pushOpacity(value: number | undefined) {
  if (value === undefined || Number.isNaN(value)) return
  pushStyle('opacity', String(Math.max(0, Math.min(100, value)) / 100))
}
function syncBox(kind: 'padding' | 'margin', value: number | undefined) {
  if (value === undefined || Number.isNaN(value)) return
  const px = `${value}px`
  const prefix = kind === 'padding' ? 'padding' : 'margin'
  const fields = kind === 'padding'
    ? ['paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft']
    : ['marginTop', 'marginRight', 'marginBottom', 'marginLeft']
  fields.forEach((field) => { (form as any)[field] = value })
  pushStyle(prefix, px)
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
function applyAiHtml(newHtml: string) {
  pendingSync = null
  selected.value = null
  treeData.value = []
  renderHtml.value = newHtml
  html.value = newHtml
  frameKey.value++
}

function wait(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function extractCurrentPageHtml(content: string): string {
  let decoded: any = content
  for (let i = 0; i < 3 && typeof decoded === 'string'; i++) {
    try {
      const parsed = JSON.parse(decoded)
      decoded = parsed
    } catch {
      break
    }
  }

  if (Array.isArray(decoded)) {
    return decoded[props.pageIndex || 0]?.html || ''
  }
  if (decoded && typeof decoded === 'object') {
    if (Array.isArray(decoded.pages)) return decoded.pages[props.pageIndex || 0]?.html || ''
    if (typeof decoded.html === 'string') return decoded.html
  }
  return typeof decoded === 'string'
    ? decoded.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\"/g, '"')
    : ''
}

async function recoverLatestPrototype(previousHtml: string) {
  for (let i = 0; i < 6; i++) {
    await wait(i === 0 ? 800 : 1800)
    try {
      const res = await getPrototype(props.prototypeId)
      const latestHtml = extractCurrentPageHtml(res.data.data?.content || '')
      if (latestHtml && latestHtml.trim() && latestHtml !== previousHtml) {
        applyAiHtml(latestHtml)
        return true
      }
    } catch {
      // 继续重试，避免刚保存完成时短暂读取失败。
    }
  }
  return false
}

function aiEditErrorMessage(error: any) {
  const serverMessage = error?.response?.data?.message
  if (serverMessage) return serverMessage
  if (error?.code === 'ECONNABORTED' || String(error?.message || '').toLowerCase().includes('timeout')) {
    return 'AI 修改耗时过长，连接已超时；如果后台稍后保存成功，刷新或重新打开原型即可看到最新版本'
  }
  if (!error?.response) return 'AI 修改连接中断，已尝试刷新服务端最新原型但未发现新版本'
  return error?.message || 'AI 修改失败'
}

function buildTargetElementDescription() {
  if (!selected.value) return undefined
  const text = typeof selected.value.text === 'string' && selected.value.text.trim()
    ? `，文字="${selected.value.text.trim().slice(0, 60)}"`
    : ''
  return `<${selected.value.tag}>${text}`
}

function closeAiEditStream() {
  aiEditEventSource?.close()
  aiEditEventSource = null
  if (streamIdleTimer) {
    clearTimeout(streamIdleTimer)
    streamIdleTimer = null
  }
  const waiters = patchAppliedWaiters
  patchAppliedWaiters = []
  waiters.forEach(resolve => resolve())
}

async function persistAiEditSnapshot(finalHtml: string, summary: string) {
  try {
    await savePrototypeAiEditSnapshot(props.prototypeId, {
      currentHtml: finalHtml,
      pageIndex: props.pageIndex || 0,
      changeSummary: summary,
    })
  } catch (error) {
    console.warn('prototype ai edit snapshot save failed', error)
    ElMessage.warning('预览已完成修改，但自动保存失败，请手动导出或刷新后重试')
  }
}

async function runStreamAiModify(text: string) {
  const res = await startPrototypeAiEditStream(props.prototypeId, {
    instruction: text,
    targetElement: buildTargetElementDescription(),
    pageIndex: props.pageIndex || 0,
    currentHtml: html.value,
  })
  const taskId = res.data.data
  if (!taskId) throw new Error('AI 修改任务创建失败')

  closeAiEditStream()
  aiPatchCount.value = 0
  lastPatchSeqReceived = 0
  lastPatchSeqApplied = 0
  patchAppliedWaiters = []

  return new Promise<boolean>((resolve, reject) => {
    let settled = false
    const settle = (fn: () => void) => {
      if (settled) return
      settled = true
      closeAiEditStream()
      fn()
    }
    const finishVisualEdit = async (message?: string) => {
      const finalSummary = message || aiSummary.value || 'AI 已根据你的描述完成修改'
      await waitForPatchesApplied(2500)
      aiSummary.value = finalSummary
      persistCurrentPreviewInBackground(finalSummary)
    }
    const scheduleIdleFinish = () => {
      if (streamIdleTimer) clearTimeout(streamIdleTimer)
      streamIdleTimer = setTimeout(async () => {
        if (settled || lastPatchSeqReceived <= 0) return
        try {
          await finishVisualEdit('AI 已完成可见修改，正在后台保存')
          settle(() => resolve(true))
        } catch (error) {
          settle(() => reject(error instanceof Error ? error : new Error('AI 修改完成状态处理失败')))
        }
      }, 3500)
    }

    aiEditEventSource = new EventSource(getTaskSseUrl(taskId))
    aiEditEventSource.addEventListener('prototype-patch', (e) => {
      try {
        const patch = JSON.parse(e.data) as PrototypePatch
        if (patch.op === 'summary') {
          aiSummary.value = patch.text || patch.summary || aiSummary.value
        } else {
          aiPatchCount.value += 1
          const seq = Number(patch.seq)
          if (!Number.isNaN(seq)) lastPatchSeqReceived = Math.max(lastPatchSeqReceived, seq)
          if (patch.summary) aiSummary.value = patch.summary
          sendPatchToFrame(patch)
          scheduleIdleFinish()
        }
      } catch (error) {
        console.warn('prototype patch parse failed', error)
      }
    })

    aiEditEventSource.addEventListener('progress', async (e) => {
      try {
        const data = JSON.parse(e.data)
        const progress = Number(data.progress)
        if (progress >= 100) {
          if (lastPatchSeqReceived <= 0) {
            throw new Error('AI 未返回可执行的原型修改指令，已切换为完整 HTML 修改')
          }
          await finishVisualEdit(data.message)
          settle(() => resolve(true))
        } else if (progress <= 0) {
          settle(() => reject(new Error(data.message || 'AI 修改失败')))
        }
      } catch (error) {
        settle(() => reject(error instanceof Error ? error : new Error('AI 修改状态解析失败')))
      }
    })

    aiEditEventSource.onerror = async () => {
      if (lastPatchSeqReceived > 0) {
        try {
          await finishVisualEdit('AI 已完成可见修改，正在后台保存')
          settle(() => resolve(true))
        } catch (error) {
          settle(() => reject(error instanceof Error ? error : new Error('AI 修改流连接中断')))
        }
        return
      }
      settle(() => reject(new Error('AI 修改流连接中断')))
    }
  })
}

async function runFullHtmlAiModify(text: string) {
  const res = await aiEditPrototype(props.prototypeId, {
    instruction: text,
    targetElement: buildTargetElementDescription(),
    pageIndex: props.pageIndex || 0,
    currentHtml: html.value,
  })
  const data = res.data.data
  applyAiHtml(data.newHtml)
  aiSummary.value = data.changeSummary || 'AI 已根据你的描述完成修改'
  aiText.value = ''
  ElMessage.success('AI 修改完成')
}

async function doAiModify() {
  const text = aiText.value.trim()
  if (!text) { ElMessage.warning('请先描述你想修改的内容'); return }
  if (!props.prototypeId) { ElMessage.error('缺少原型 ID，无法调用 AI 修改'); return }
  if (aiLoading.value) return

  aiLoading.value = true
  aiSummary.value = ''
  aiPatchCount.value = 0
  const beforeHtml = html.value
  try {
    await runStreamAiModify(text)
    aiText.value = ''
    ElMessage.success(aiPatchCount.value > 0 ? `AI 修改完成，已应用 ${aiPatchCount.value} 条指令` : 'AI 修改完成')
  } catch (error: any) {
    console.warn('stream prototype edit failed, fallback to full html edit', error)
    try {
      await runFullHtmlAiModify(text)
    } catch (fallbackError: any) {
      const recovered = await recoverLatestPrototype(beforeHtml)
      if (recovered) {
        aiSummary.value = 'AI 修改已在服务端完成，预览已自动刷新'
        aiText.value = ''
        ElMessage.success('AI 修改完成，已刷新预览')
      } else {
        ElMessage.error(aiEditErrorMessage(fallbackError))
      }
    }
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
  frameReady.value = false
  selected.value = null
  treeData.value = []
  window.setTimeout(() => {
    if (!iframeRef.value?.contentWindow) return
    frameReady.value = true
    post({ type: 'mode', value: editMode.value })
    post({ type: 'requestTree' })
    flushQueuedPatches()
  }, 80)
}

onMounted(() => window.addEventListener('message', onMessage))
onBeforeUnmount(() => {
  window.removeEventListener('message', onMessage)
  if (syncTimer) clearTimeout(syncTimer)
  if (snapshotTimer) clearTimeout(snapshotTimer)
  if (streamIdleTimer) clearTimeout(streamIdleTimer)
  closeAiEditStream()
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

        <div v-if="regenerating" class="canvas-mask">
          <el-icon class="spin" size="28"><MagicStick /></el-icon>
          <p>AI 正在生成原型…</p>
        </div>
        <div v-if="aiLoading" class="canvas-live-status">
          <el-icon class="spin" size="16"><MagicStick /></el-icon>
          <span>{{ aiLoadingText }}</span>
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

              <el-tabs v-model="activePropTab" class="prop-tabs">
                <el-tab-pane label="文字" name="text">
                  <div class="tab-fields">
                    <div v-if="form.editableText" class="field">
                      <label>文字内容</label>
                      <el-input v-model="form.text" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" @input="pushText" />
                    </div>

                    <div v-if="form.isLink" class="field">
                      <label>链接地址</label>
                      <el-input v-model="form.href" placeholder="https:// 或 #" @input="pushHref" />
                    </div>

                    <div class="field-row">
                      <div class="field">
                        <label>字体</label>
                        <el-select v-model="form.fontFamily" size="small" @change="pushStyle('fontFamily', form.fontFamily)">
                          <el-option v-for="f in FONT_FAMILIES" :key="f.value" :label="f.label" :value="f.value" />
                        </el-select>
                      </div>
                      <div class="field">
                        <label>字重</label>
                        <el-select v-model="form.fontWeight" size="small" @change="pushStyle('fontWeight', form.fontWeight)">
                          <el-option v-for="w in FONT_WEIGHTS" :key="w.value" :label="w.label" :value="w.value" />
                        </el-select>
                      </div>
                    </div>

                    <div class="field-row three">
                      <div class="field">
                        <label>字号</label>
                        <el-input-number v-model="form.fontSize" class="mini-num" size="small" :min="8" :max="96" controls-position="right" @change="pushPxStyle('fontSize', form.fontSize)" />
                      </div>
                      <div class="field">
                        <label>行高</label>
                        <el-input-number v-model="form.lineHeight" class="mini-num" size="small" :min="0" :max="140" controls-position="right" @change="pushPxStyle('lineHeight', form.lineHeight)" />
                      </div>
                      <div class="field">
                        <label>字距</label>
                        <el-input-number v-model="form.letterSpacing" class="mini-num" size="small" :min="-10" :max="40" controls-position="right" @change="pushPxStyle('letterSpacing', form.letterSpacing)" />
                      </div>
                    </div>

                    <div class="field">
                      <label>文字颜色</label>
                      <el-color-picker v-model="form.color" show-alpha @change="pushStyle('color', form.color)" />
                    </div>

                    <div class="field-row">
                      <div class="field">
                        <label>对齐</label>
                        <el-radio-group v-model="form.textAlign" size="small" @change="pushStyle('textAlign', form.textAlign)">
                          <el-radio-button value="left">左</el-radio-button>
                          <el-radio-button value="center">中</el-radio-button>
                          <el-radio-button value="right">右</el-radio-button>
                        </el-radio-group>
                      </div>
                      <div class="field">
                        <label>样式</label>
                        <el-radio-group v-model="form.fontStyle" size="small" @change="pushStyle('fontStyle', form.fontStyle)">
                          <el-radio-button value="normal">常规</el-radio-button>
                          <el-radio-button value="italic">斜体</el-radio-button>
                        </el-radio-group>
                      </div>
                    </div>

                    <div class="field">
                      <label>装饰</label>
                      <el-radio-group v-model="form.textDecoration" size="small" @change="pushStyle('textDecoration', form.textDecoration)">
                        <el-radio-button value="none">无</el-radio-button>
                        <el-radio-button value="underline">下划线</el-radio-button>
                        <el-radio-button value="line-through">删除线</el-radio-button>
                      </el-radio-group>
                    </div>
                  </div>
                </el-tab-pane>

                <el-tab-pane label="外观" name="appearance">
                  <div class="tab-fields">
                    <div class="field-row">
                      <div class="field">
                        <label>背景颜色</label>
                        <el-color-picker v-model="form.backgroundColor" show-alpha @change="pushStyle('backgroundColor', form.backgroundColor || 'transparent')" />
                      </div>
                      <div class="field">
                        <label>透明度 <span class="val">{{ form.opacity }}%</span></label>
                        <el-slider v-model="form.opacity" :min="0" :max="100" @input="pushOpacity(form.opacity)" />
                      </div>
                    </div>

                    <div class="field-row three">
                      <div class="field">
                        <label>圆角</label>
                        <el-input-number v-model="form.borderRadius" class="mini-num" size="small" :min="0" :max="120" controls-position="right" @change="pushPxStyle('borderRadius', form.borderRadius)" />
                      </div>
                      <div class="field">
                        <label>边框宽</label>
                        <el-input-number v-model="form.borderWidth" class="mini-num" size="small" :min="0" :max="24" controls-position="right" @change="pushPxStyle('borderWidth', form.borderWidth)" />
                      </div>
                      <div class="field">
                        <label>边框样式</label>
                        <el-select v-model="form.borderStyle" size="small" @change="pushStyle('borderStyle', form.borderStyle)">
                          <el-option v-for="s in BORDER_STYLES" :key="s" :label="s" :value="s" />
                        </el-select>
                      </div>
                    </div>

                    <div class="field-row">
                      <div class="field">
                        <label>边框颜色</label>
                        <el-color-picker v-model="form.borderColor" show-alpha @change="pushStyle('borderColor', form.borderColor)" />
                      </div>
                      <div class="field">
                        <label>阴影</label>
                        <el-radio-group v-model="form.shadow" size="small" @change="pushStyle('boxShadow', SHADOWS[form.shadow])">
                          <el-radio-button v-for="(_, k) in SHADOWS" :key="k" :value="k">{{ k }}</el-radio-button>
                        </el-radio-group>
                      </div>
                    </div>
                  </div>
                </el-tab-pane>

                <el-tab-pane label="盒模型" name="box">
                  <div class="tab-fields">
                    <div class="box-section">
                      <div class="field-title">内边距</div>
                      <div class="quad-grid">
                        <el-input-number v-model="form.paddingTop" size="small" :min="0" :max="200" controls-position="right" @change="pushPxStyle('paddingTop', form.paddingTop)" />
                        <el-input-number v-model="form.paddingRight" size="small" :min="0" :max="200" controls-position="right" @change="pushPxStyle('paddingRight', form.paddingRight)" />
                        <el-input-number v-model="form.paddingBottom" size="small" :min="0" :max="200" controls-position="right" @change="pushPxStyle('paddingBottom', form.paddingBottom)" />
                        <el-input-number v-model="form.paddingLeft" size="small" :min="0" :max="200" controls-position="right" @change="pushPxStyle('paddingLeft', form.paddingLeft)" />
                      </div>
                      <div class="quick-line">
                        <el-button size="small" text @click="syncBox('padding', form.paddingTop)">四边同步</el-button>
                      </div>
                    </div>

                    <div class="box-section">
                      <div class="field-title">外边距</div>
                      <div class="quad-grid">
                        <el-input-number v-model="form.marginTop" size="small" :min="-120" :max="200" controls-position="right" @change="pushPxStyle('marginTop', form.marginTop)" />
                        <el-input-number v-model="form.marginRight" size="small" :min="-120" :max="200" controls-position="right" @change="pushPxStyle('marginRight', form.marginRight)" />
                        <el-input-number v-model="form.marginBottom" size="small" :min="-120" :max="200" controls-position="right" @change="pushPxStyle('marginBottom', form.marginBottom)" />
                        <el-input-number v-model="form.marginLeft" size="small" :min="-120" :max="200" controls-position="right" @change="pushPxStyle('marginLeft', form.marginLeft)" />
                      </div>
                      <div class="quick-line">
                        <el-button size="small" text @click="syncBox('margin', form.marginTop)">四边同步</el-button>
                      </div>
                    </div>

                    <div class="field-row three">
                      <div class="field">
                        <label>强制宽度</label>
                        <el-input-number v-model="form.width" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushWidthStyle(form.width)" />
                      </div>
                      <div class="field">
                        <label>高度</label>
                        <el-input-number v-model="form.height" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushPxStyle('height', form.height)" />
                      </div>
                      <div class="field">
                        <label>间距</label>
                        <el-input-number v-model="form.gap" class="mini-num" size="small" :min="0" :max="160" controls-position="right" @change="pushPxStyle('gap', form.gap)" />
                      </div>
                    </div>

                    <div class="field">
                      <label>Flex 基准宽度</label>
                      <el-input-number v-model="form.flexBasis" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushPxStyle('flexBasis', form.flexBasis)" />
                    </div>

                    <div class="field-row four">
                      <div class="field">
                        <label>最小宽</label>
                        <el-input-number v-model="form.minWidth" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushPxStyle('minWidth', form.minWidth)" />
                      </div>
                      <div class="field">
                        <label>最大宽</label>
                        <el-input-number v-model="form.maxWidth" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushPxStyle('maxWidth', form.maxWidth)" />
                      </div>
                      <div class="field">
                        <label>最小高</label>
                        <el-input-number v-model="form.minHeight" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushPxStyle('minHeight', form.minHeight)" />
                      </div>
                      <div class="field">
                        <label>最大高</label>
                        <el-input-number v-model="form.maxHeight" class="mini-num" size="small" :min="0" :max="2400" controls-position="right" @change="pushPxStyle('maxHeight', form.maxHeight)" />
                      </div>
                    </div>
                  </div>
                </el-tab-pane>

                <el-tab-pane label="布局" name="layout">
                  <div class="tab-fields">
                    <div class="field-row three">
                      <div class="field">
                        <label>显示</label>
                        <el-select v-model="form.display" size="small" @change="pushStyle('display', form.display)">
                          <el-option v-for="d in DISPLAY_OPTIONS" :key="d" :label="d" :value="d" />
                        </el-select>
                      </div>
                      <div class="field">
                        <label>方向</label>
                        <el-select v-model="form.flexDirection" size="small" @change="pushStyle('flexDirection', form.flexDirection)">
                          <el-option v-for="d in FLEX_DIRECTIONS" :key="d.value" :label="d.label" :value="d.value" />
                        </el-select>
                      </div>
                      <div class="field">
                        <label>定位</label>
                        <el-select v-model="form.position" size="small" @change="pushStyle('position', form.position)">
                          <el-option v-for="p in POSITION_OPTIONS" :key="p" :label="p" :value="p" />
                        </el-select>
                      </div>
                    </div>

                    <div class="field-row">
                      <div class="field">
                        <label>主轴对齐</label>
                        <el-select v-model="form.justifyContent" size="small" @change="pushStyle('justifyContent', form.justifyContent)">
                          <el-option v-for="j in JUSTIFY_OPTIONS" :key="j.value" :label="j.label" :value="j.value" />
                        </el-select>
                      </div>
                      <div class="field">
                        <label>交叉轴</label>
                        <el-select v-model="form.alignItems" size="small" @change="pushStyle('alignItems', form.alignItems)">
                          <el-option v-for="a in ALIGN_OPTIONS" :key="a.value" :label="a.label" :value="a.value" />
                        </el-select>
                      </div>
                    </div>

                    <div class="box-section">
                      <div class="field-title">偏移</div>
                      <div class="quad-grid">
                        <el-input-number v-model="form.top" size="small" :min="-1000" :max="1000" controls-position="right" @change="pushPxStyle('top', form.top)" />
                        <el-input-number v-model="form.right" size="small" :min="-1000" :max="1000" controls-position="right" @change="pushPxStyle('right', form.right)" />
                        <el-input-number v-model="form.bottom" size="small" :min="-1000" :max="1000" controls-position="right" @change="pushPxStyle('bottom', form.bottom)" />
                        <el-input-number v-model="form.left" size="small" :min="-1000" :max="1000" controls-position="right" @change="pushPxStyle('left', form.left)" />
                      </div>
                    </div>

                    <div class="field">
                      <label>层级</label>
                      <el-input-number v-model="form.zIndex" class="mini-num" size="small" :min="-10" :max="9999" controls-position="right" @change="pushNumberStyle('zIndex', form.zIndex)" />
                    </div>
                  </div>
                </el-tab-pane>
              </el-tabs>
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
.canvas-live-status {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 4;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: min(360px, calc(100% - 28px));
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid rgba(245, 78, 0, 0.18);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 8px 24px rgba(38, 37, 30, 0.12);
  color: #26251e;
  font-size: 12px;
  line-height: 1.4;
  pointer-events: none;
}
.canvas-live-status span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.prop-inner { padding: 0 14px 20px; display: flex; flex-direction: column; gap: 12px; }

.sel-chip {
  display: inline-flex; align-items: center; gap: 8px; align-self: flex-start;
  padding: 3px 6px 3px 10px; border-radius: 6px;
  background: rgba(245, 78, 0, 0.1); color: #f54e00;
  font-family: ui-monospace, monospace; font-size: 12px; font-weight: 600;
}
.chip-close { cursor: pointer; border-radius: 4px; }
.chip-close:hover { background: rgba(245, 78, 0, 0.2); }

.field { display: flex; flex-direction: column; gap: 6px; }
.field-row { display: flex; gap: 12px; }
.field-row .field { flex: 1; }
.field-row.three .field { min-width: 0; }
.field-row.four {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.field > label {
  font-size: 12px; color: rgba(38, 37, 30, 0.6); font-weight: 500;
  display: flex; justify-content: space-between; align-items: center;
}
.field .val { color: #f54e00; font-weight: 600; }
.prop-tabs { margin-top: -4px; }
.prop-tabs :deep(.el-tabs__header) { margin: 0 0 10px; }
.prop-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: rgba(38, 37, 30, 0.08); }
.prop-tabs :deep(.el-tabs__item) { height: 30px; padding: 0 12px; font-size: 12px; }
.prop-tabs :deep(.el-tabs__active-bar) { background: #f54e00; }
.tab-fields { display: flex; flex-direction: column; gap: 12px; }
.field-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: rgba(38, 37, 30, 0.58);
}
.box-section {
  padding: 10px;
  border-radius: 8px;
  background: #fbfbf9;
  border: 1px solid rgba(38, 37, 30, 0.06);
}
.quad-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.quick-line {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}
.mini-num,
.quad-grid :deep(.el-input-number) {
  width: 100%;
}
.mini-num :deep(.el-input__inner),
.quad-grid :deep(.el-input__inner) {
  padding-left: 4px;
  padding-right: 24px;
  font-size: 12px;
}

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
