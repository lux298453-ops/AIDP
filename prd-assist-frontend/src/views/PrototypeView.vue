<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { getTaskSseUrl, getTaskById } from '@/api/task'
import client from '@/api/client'

// ==================== 页面模型 ====================
interface ProtoPage {
  title: string
  order: number
  html: string
}

// ==================== 表单 ====================
const description = ref('')
const platform = ref('APP')
const generateMode = ref<'single' | 'multi'>('single')

const platforms = [
  { key: 'APP', label: 'APP', icon: 'Iphone' },
  { key: 'WEB', label: 'Web', icon: 'Monitor' },
  { key: 'PAD', label: 'Pad', icon: 'Platform' },
]

// ==================== 状态 ====================
const loading = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const showPreview = ref(false)
const viewMode = ref<'preview' | 'edit'>('preview')
const editSource = ref('')
const saving = ref(false)

// 单页面
const singleHtml = ref('')
// 多页面
const pages = ref<ProtoPage[]>([])
const currentPageIndex = ref(0)

const currentHtml = computed(() => {
  if (pages.value.length > 0) return pages.value[currentPageIndex.value]?.html || ''
  return singleHtml.value
})
const isMultiPage = computed(() => pages.value.length > 0)

let taskId: number | null = null
let resultId: number | null = null
const sandboxAttrs = 'allow-scripts allow-same-origin'

const canGenerate = computed(() => description.value.trim().length > 0)

// ==================== 交互编辑 ====================
function enterEditMode() {
  editSource.value = currentHtml.value
  viewMode.value = 'edit'
}
function enterPreviewMode() {
  // 将编辑后的内容应用到当前页面
  if (pages.value.length > 0) {
    pages.value[currentPageIndex.value].html = editSource.value
  } else {
    singleHtml.value = editSource.value
  }
  viewMode.value = 'preview'
}
async function saveEdits() {
  if (!resultId) return
  // 先应用编辑
  if (viewMode.value === 'edit') enterPreviewMode()
  saving.value = true
  try {
    // 保存时，单页面直接传 HTML，多页面序列化为 JSON 数组
    let content: string
    if (pages.value.length > 0) {
      content = JSON.stringify(pages.value.map(p => ({ title: p.title, order: p.order, html: p.html })))
    } else {
      content = singleHtml.value
    }
    await client.put(`/prototype/${resultId}`, { content })
    ElMessage.success('修改已保存')
  } catch { ElMessage.error('保存失败') }
  finally { saving.value = false }
}

// ==================== 提交 ====================
async function handleGenerate() {
  if (!description.value.trim()) { ElMessage.warning('请输入功能描述'); return }
  loading.value = true; singleHtml.value = ''; pages.value = []; showPreview.value = false; viewMode.value = 'preview'
  progress.value = 0; progressMsg.value = ''; currentPageIndex.value = 0; resultId = null

  try {
    const res = await client.post('/prototype/generate', {
      description: description.value,
      prototypeType: generateMode.value === 'single' ? 'SINGLE_PAGE' : 'MULTI_PAGE',
      platform: platform.value,
    })
    taskId = res.data.data.taskId; startSse()
  } catch { loading.value = false }
}

function startSse() {
  if (!taskId) return
  const es = new EventSource(getTaskSseUrl(taskId))
  es.addEventListener('progress', (e) => {
    const d = JSON.parse(e.data); progress.value = d.progress; progressMsg.value = d.message
    if (d.progress <= 0 && d.message && d.message.includes('失败')) {
      es.close(); loading.value = false; ElMessage.error(d.message)
      return
    }
    if (d.progress >= 100) { es.close(); loading.value = false; ElMessage.success('原型生成完成'); fetchResult() }
  })
  es.onerror = () => { es.close(); loading.value = false }
}

async function fetchResult() {
  if (!taskId) return
  const t = setInterval(async () => {
    const r = await getTaskById(taskId!)
    if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
      clearInterval(t)
      resultId = r.data.data.resultRefId
      const protoRes = await client.get(`/prototype/${resultId}`)
      parseContent(protoRes.data.data.content || '', protoRes.data.data.prototypeType)
      showPreview.value = true
    }
    if (r.data.data.status === 'FAILED') { ElMessage.error(r.data.data.errorMessage || '失败'); clearInterval(t) }
  }, 2000)
}

/** 导出原型为 HTML 文件 */
async function exportPrototype() {
  if (!resultId) return
  // 如果在编辑模式，先应用编辑
  if (viewMode.value === 'edit') enterPreviewMode()
  try {
    const res = await client.get(`/prototype/${resultId}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data], { type: 'text/html' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `原型_${resultId}.html`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

/** 解析内容：JSON 数组 → 多页面，纯 HTML → 单页面 */
function parseContent(content: string, protoType: string) {
  if (!content) { singleHtml.value = '<p>暂无内容</p>'; return }

  let decoded: any = content
  for (let i = 0; i < 3; i++) {
    if (typeof decoded !== 'string') break
    try {
      const parsed = JSON.parse(decoded)
      if (typeof parsed === 'string') { decoded = parsed; continue }
      decoded = parsed
      break
    } catch { break }
  }

  if (typeof decoded === 'string') {
    let html = decoded
    html = html.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\"/g, '"')
    if (!html.trim().startsWith('<') && html.trim().startsWith('[')) {
      try { const arr = JSON.parse(html); if (Array.isArray(arr)) decoded = arr } catch { /* keep */ }
    }
  }

  if (typeof decoded === 'object' && decoded !== null) {
    if (Array.isArray(decoded) && decoded.length > 0) {
      pages.value = decoded.map((p: any) => ({ title: p.title || `页面 ${p.order || 1}`, order: p.order || 1, html: p.html || '' }))
      pages.value.sort((a, b) => a.order - b.order); return
    }
    if (decoded.pages && Array.isArray(decoded.pages)) {
      pages.value = decoded.pages.map((p: any) => ({ title: p.title || `页面 ${p.order || 1}`, order: p.order || 1, html: p.html || '' }))
      pages.value.sort((a, b) => a.order - b.order); return
    }
    if (decoded.html) { singleHtml.value = decoded.html; return }
    singleHtml.value = '<pre>' + JSON.stringify(decoded, null, 2).replace(/</g, '&lt;') + '</pre>'; return
  }

  if (protoType === 'MULTI_PAGE' && typeof content === 'string') {
    try {
      const arr = JSON.parse(content)
      if (Array.isArray(arr) && arr.length > 0) {
        pages.value = arr.map((p: any) => ({ title: p.title || `页面 ${p.order || 1}`, order: p.order || 1, html: p.html || '' }))
        pages.value.sort((a, b) => a.order - b.order); return
      }
    } catch { /* fall through */ }
  }

  singleHtml.value = typeof decoded === 'string' ? decoded : String(content)
}

function selectPage(index: number) { currentPageIndex.value = index }
function closePreview() { showPreview.value = false; pages.value = []; singleHtml.value = ''; viewMode.value = 'preview' }
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧面板 ====== -->
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">原型图生成</h2>
          <el-icon size="18" color="#909399"><Clock /></el-icon>
        </div>

        <div class="form-group">
          <label class="form-label">生成模式</label>
          <div class="mode-btns">
            <div class="mode-btn" :class="{ active: generateMode === 'single' }" @click="generateMode = 'single'">单页面</div>
            <div class="mode-btn" :class="{ active: generateMode === 'multi' }" @click="generateMode = 'multi'">多页面</div>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">终端类型</label>
          <div class="platform-cards">
            <div v-for="p in platforms" :key="p.key" class="platform-card" :class="{ active: platform === p.key }" @click="platform = p.key">
              <el-icon size="24"><component :is="p.icon" /></el-icon>
              <span>{{ p.label }}</span>
            </div>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">功能描述 <span class="required">*</span></label>
          <el-input v-model="description" type="textarea" :rows="10" placeholder="描述你想要生成的页面功能..." maxlength="2000" show-word-limit />
        </div>

        <el-button class="btn-generate" :loading="loading" :disabled="!canGenerate" @click="handleGenerate">一键生成</el-button>
        <el-progress v-if="loading" :percentage="progress" :stroke-width="6" style="margin-top:12px" />
        <p v-if="progressMsg" style="color:#909399;font-size:13px;margin-top:6px">{{ progressMsg }}</p>
      </div>

      <!-- ====== 右侧预览区 ====== -->
      <div class="preview-panel">
        <template v-if="showPreview && (singleHtml || pages.length > 0)">
          <!-- 工具栏 -->
          <div class="preview-toolbar">
            <span>{{ isMultiPage ? `多页面原型 · ${pages.length} 页` : `原型预览 (${platform})` }}</span>
            <div style="display:flex;gap:8px">
              <template v-if="viewMode === 'preview'">
                <el-button type="warning" size="small" @click="enterEditMode">
                  <el-icon><Edit /></el-icon> 编辑源码
                </el-button>
                <el-button type="primary" size="small" @click="exportPrototype">
                  <el-icon><Download /></el-icon> 导出 HTML
                </el-button>
              </template>
              <template v-else>
                <el-button type="success" size="small" @click="enterPreviewMode">
                  <el-icon><View /></el-icon> 预览
                </el-button>
                <el-button type="primary" size="small" :loading="saving" @click="saveEdits">
                  <el-icon><Check /></el-icon> 保存修改
                </el-button>
              </template>
              <el-button type="text" size="small" @click="closePreview">关闭</el-button>
            </div>
          </div>
          <div class="preview-body" :class="{ 'has-pages': isMultiPage }">
            <!-- 多页面：左侧缩略图导航 -->
            <div v-if="isMultiPage" class="page-nav">
              <div
                v-for="(page, idx) in pages" :key="idx"
                class="page-thumb"
                :class="{ active: idx === currentPageIndex }"
                @click="selectPage(idx)"
              >
                <iframe :srcdoc="page.html" :sandbox="sandboxAttrs" class="thumb-frame" scrolling="no" />
                <span class="thumb-label">{{ page.title }}</span>
              </div>
            </div>
            <!-- 预览模式：iframe 渲染 -->
            <iframe v-if="viewMode === 'preview' && currentHtml" :srcdoc="currentHtml" :sandbox="sandboxAttrs" class="preview-frame" :key="'pv-' + currentPageIndex" title="原型预览" />
            <!-- 编辑模式：HTML 源码编辑器 -->
            <div v-if="viewMode === 'edit'" class="edit-area">
              <div class="edit-header">
                <span>HTML 源码编辑 — {{ isMultiPage ? pages[currentPageIndex]?.title : '单页面' }}</span>
                <span class="edit-hint">直接修改 HTML 后点击「预览」查看效果，满意后「保存修改」</span>
              </div>
              <el-input
                v-model="editSource"
                type="textarea"
                class="edit-textarea"
                :autosize="false"
                placeholder="编辑 HTML 源码..."
              />
            </div>
          </div>
        </template>
        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="#dcdfe6"><PictureFilled /></el-icon>
            <p class="placeholder-title">交互原型即将呈现</p>
            <p class="placeholder-desc">在左侧描述功能需求并选择生成模式，AI 将生成可交互的 HTML 原型</p>
          </div>
        </template>
        <!-- 生成中骨架屏 -->
        <div v-if="loading" class="preview-placeholder">
          <el-skeleton animated style="width:80%;max-width:500px">
            <template #template>
              <div style="display:flex;flex-direction:column;gap:16px;align-items:center">
                <el-skeleton-item variant="rect" style="width:100%;height:200px;border-radius:10px" />
                <el-skeleton-item variant="text" style="width:60%" />
                <el-skeleton-item variant="text" style="width:40%" />
              </div>
            </template>
          </el-skeleton>
          <p style="color:#909399;font-size:14px;margin-top:20px">{{ progressMsg || 'AI 正在生成原型，请稍候…' }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-container { padding: 24px 48px; height: 100%; box-sizing: border-box; }
.workspace { display: flex; height: 100%; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 12px rgba(0,0,0,0.06); }
.config-panel { width: 420px; flex-shrink: 0; padding: 28px 24px; background: #fff; border-right: 1px solid #ebeef5; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.module-title { font-size: 20px; font-weight: 700; color: #303133; margin: 0; }
.form-group { margin-bottom: 18px; }
.form-label { display: block; font-size: 14px; font-weight: 500; color: #303133; margin-bottom: 8px; }
.required { color: #f56c6c; }

.mode-btns { display: flex; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; }
.mode-btn { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #f5f7fa; color: #909399; transition: all .2s; user-select: none; }
.mode-btn.active { background: #409eff; color: #fff; font-weight: 500; }

.platform-cards { display: flex; gap: 10px; }
.platform-card { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 18px 8px; border: 1.5px solid #ebeef5; border-radius: 10px; cursor: pointer; background: #fafafa; color: #909399; font-size: 13px; transition: all .2s; }
.platform-card:hover { border-color: #b3d8ff; }
.platform-card.active { border-color: #409eff; background: #ecf5ff; color: #409eff; }

.btn-generate { width: 100%; height: 46px; font-size: 15px; border-radius: 10px; background: #c8c9cc !important; border-color: #c8c9cc !important; color: #fff !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #409eff !important; border-color: #409eff !important; }

:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }

/* ====== 预览区 ====== */
.preview-panel { flex: 1; background: #f5f7fa; display: flex; flex-direction: column; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; flex: 1; }
.placeholder-title { font-size: 16px; color: #909399; margin: 20px 0 8px; }
.placeholder-desc { font-size: 13px; color: #c0c4cc; margin: 0; max-width: 320px; text-align: center; line-height: 1.7; }
.preview-toolbar { display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; background: #fff; border-bottom: 1px solid #ebeef5; font-size: 14px; color: #303133; flex-shrink: 0; }

.preview-body { flex: 1; display: flex; overflow: hidden; }
.preview-body.has-pages { gap: 0; }

/* 页面缩略图导航栏 */
.page-nav { width: 140px; flex-shrink: 0; overflow-y: auto; background: #fff; border-right: 1px solid #ebeef5; padding: 8px; display: flex; flex-direction: column; gap: 8px; }
.page-thumb { cursor: pointer; border: 2px solid transparent; border-radius: 6px; overflow: hidden; transition: border-color .2s; display: flex; flex-direction: column; }
.page-thumb.active { border-color: #409eff; }
.page-thumb:hover { border-color: #b3d8ff; }
.thumb-frame { width: 100%; height: 90px; border: none; pointer-events: none; transform: scale(0.35); transform-origin: top left; width: 340px; }
.thumb-label { font-size: 11px; color: #606266; text-align: center; padding: 4px 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.preview-frame { flex: 1; width: 100%; border: none; }

/* ====== 编辑模式 ====== */
.edit-area { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.edit-header { display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; background: #fdf6ec; border-bottom: 1px solid #faecd8; font-size: 13px; color: #e6a23c; flex-shrink: 0; }
.edit-hint { font-size: 12px; color: #c0c4cc; }
.edit-textarea { flex: 1; }
.edit-textarea :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 400px;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  border-radius: 0;
  border: none;
  resize: none;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 16px;
}
</style>
