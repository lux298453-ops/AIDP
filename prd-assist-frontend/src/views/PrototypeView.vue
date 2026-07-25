<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Clock, PictureFilled, Iphone, Monitor, Platform, UploadFilled, Delete, Loading,
} from '@element-plus/icons-vue'
import { getTaskSseUrl, getTaskById } from '@/api/task'
import client from '@/api/client'
import PrototypeResultView from './PrototypeResultView.vue'

const route = useRoute()

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
  { key: 'APP', label: 'APP', icon: Iphone },
  { key: 'WEB', label: 'Web', icon: Monitor },
  { key: 'PAD', label: 'Pad', icon: Platform },
]

// ==================== 风格参考图 ====================
const imageInput = ref<HTMLInputElement | null>(null)
const referenceImage = ref<File | null>(null)
const referencePreviewUrl = ref<string>('')
const imageDragOver = ref(false)

const ACCEPTED_IMAGE_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif']
const ACCEPTED_EXTS = ['.jpg', '.jpeg', '.png', '.webp', '.gif']
const MAX_IMAGE_SIZE = 8 * 1024 * 1024 // 8MB

function isAcceptedImage(file: File): boolean {
  const name = file.name.toLowerCase()
  const extOk = ACCEPTED_EXTS.some(e => name.endsWith(e))
  const typeOk = !file.type || ACCEPTED_IMAGE_TYPES.includes(file.type.toLowerCase())
  return extOk && typeOk
}

function setReferenceImage(file: File) {
  if (!isAcceptedImage(file)) {
    ElMessage.warning('仅支持 jpg / png / webp / gif 图片')
    return
  }
  if (file.size > MAX_IMAGE_SIZE) {
    ElMessage.warning('参考图不能超过 8MB')
    return
  }
  clearReferenceImage()
  referenceImage.value = file
  referencePreviewUrl.value = URL.createObjectURL(file)
  ElMessage.success(`已选择参考图: ${file.name}`)
}

function triggerImageUpload() {
  imageInput.value?.click()
}

function onImageFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    setReferenceImage(input.files[0])
  }
  // 允许再次选择同一文件
  input.value = ''
}

function clearReferenceImage(e?: Event) {
  e?.stopPropagation()
  if (referencePreviewUrl.value) {
    URL.revokeObjectURL(referencePreviewUrl.value)
    referencePreviewUrl.value = ''
  }
  referenceImage.value = null
  if (imageInput.value) imageInput.value.value = ''
}

function onImageDragOver(e: DragEvent) {
  e.preventDefault()
  imageDragOver.value = true
}
function onImageDragLeave(e: DragEvent) {
  e.preventDefault()
  imageDragOver.value = false
}
function onImageDrop(e: DragEvent) {
  e.preventDefault()
  imageDragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) setReferenceImage(f)
}

onBeforeUnmount(() => {
  if (referencePreviewUrl.value) URL.revokeObjectURL(referencePreviewUrl.value)
})

// ==================== 状态 ====================
const loading = ref(false)
/** 从文档列表打开历史原型时的加载态（与 AI 生成 loading 分离，避免遮罩文案错误） */
const opening = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const liveMessages = ref<string[]>([])
const showPreview = ref(false)

// 单页面
const singleHtml = ref('')
// 多页面
const pages = ref<ProtoPage[]>([])
const currentPageIndex = ref(0)

const isMultiPage = computed(() => pages.value.length > 0)
const pageTitles = computed(() => pages.value.map(p => p.title || ''))

/**
 * 与右侧组件双向绑定的「当前页 HTML」。
 */
const currentHtml = computed<string>({
  get() {
    return isMultiPage.value
      ? (pages.value[currentPageIndex.value]?.html || '')
      : singleHtml.value
  },
  set(v) {
    if (isMultiPage.value) {
      const p = pages.value[currentPageIndex.value]
      if (p) p.html = v
    } else {
      singleHtml.value = v
    }
  },
})

let taskId: number | null = null
const resultId = ref<number | null>(null)
const openingPrototypeId = ref<number | null>(null)

const canGenerate = computed(() => description.value.trim().length > 0)

// ==================== 自动保存 ====================
let saveTimer: ReturnType<typeof setTimeout> | null = null
watch([singleHtml, pages], () => {
  if (!resultId.value) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(persist, 1200)
}, { deep: true })

async function persist() {
  if (!resultId.value) return
  const content = isMultiPage.value
    ? JSON.stringify(pages.value.map(p => ({ title: p.title, order: p.order, html: p.html })))
    : singleHtml.value
  try {
    await client.put(`/prototype/${resultId.value}`, { content })
  } catch {
    // 保存失败不打断编辑
  }
}

// ==================== 提交 ====================
async function handleGenerate() {
  if (!description.value.trim()) { ElMessage.warning('请输入功能描述'); return }
  loading.value = true
  singleHtml.value = ''
  pages.value = []
  currentPageIndex.value = 0
  resultId.value = null
  progress.value = 0
  progressMsg.value = '正在提交原型生成任务...'
  liveMessages.value = ['正在提交原型生成任务...']

  try {
    const prototypeType = generateMode.value === 'single' ? 'SINGLE_PAGE' : 'MULTI_PAGE'

    let res
    if (referenceImage.value) {
      // 带参考图：multipart
      const fd = new FormData()
      fd.append('description', description.value)
      fd.append('prototypeType', prototypeType)
      fd.append('platform', platform.value)
      fd.append('referenceImage', referenceImage.value)
      res = await client.post('/prototype/generate', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 60000,
      })
    } else {
      // 无图：JSON
      res = await client.post('/prototype/generate', {
        description: description.value,
        prototypeType,
        platform: platform.value,
      })
    }
    taskId = res.data.data.taskId
    startSse()
  } catch {
    loading.value = false
  }
}

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 原型生成服务...')
  const es = new EventSource(getTaskSseUrl(taskId))
  es.addEventListener('progress', (e) => {
    const d = JSON.parse((e as MessageEvent).data)
    progress.value = d.progress
    progressMsg.value = d.message
    appendLiveMessage(d.message)
    if (d.progress <= 0 && d.message && d.message.includes('失败')) {
      es.close(); loading.value = false; ElMessage.error(d.message)
      return
    }
    if (d.progress >= 100) { es.close(); loading.value = false; appendLiveMessage('生成完成，正在加载原型预览...'); ElMessage.success('原型生成完成'); fetchResult() }
  })
  es.onerror = () => { es.close(); loading.value = false }
}

function appendLiveMessage(message?: string) {
  const text = (message || '').trim()
  if (!text) return
  if (liveMessages.value[liveMessages.value.length - 1] === text) return
  liveMessages.value = [...liveMessages.value.slice(-5), text]
}

async function fetchResult() {
  if (!taskId) return
  const t = setInterval(async () => {
    const r = await getTaskById(taskId!)
    if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
      clearInterval(t)
      resultId.value = r.data.data.resultRefId!
      const protoRes = await client.get(`/prototype/${resultId.value}`)
      parseContent(protoRes.data.data.content || '', protoRes.data.data.prototypeType)
      showPreview.value = true
    }
    if (r.data.data.status === 'FAILED') { ElMessage.error(r.data.data.errorMessage || '失败'); clearInterval(t) }
  }, 2000)
}

/** 解析内容：JSON 数组 → 多页面，纯 HTML → 单页面 */
function parseContent(content: string, protoType: string) {
  // 每次解析前清空，避免上一次多页状态污染单页（或反之）
  singleHtml.value = ''
  pages.value = []
  currentPageIndex.value = 0

  if (!content) {
    singleHtml.value = '<p style="padding:24px;color:#999">暂无内容</p>'
    return
  }

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
      pages.value = decoded.map((p: any, i: number) => ({
        title: p.title || `页面 ${p.order || i + 1}`,
        order: p.order || i + 1,
        html: p.html || '',
      }))
      pages.value.sort((a, b) => a.order - b.order)
      generateMode.value = 'multi'
      return
    }
    if (decoded.pages && Array.isArray(decoded.pages)) {
      pages.value = decoded.pages.map((p: any, i: number) => ({
        title: p.title || `页面 ${p.order || i + 1}`,
        order: p.order || i + 1,
        html: p.html || '',
      }))
      pages.value.sort((a, b) => a.order - b.order)
      generateMode.value = 'multi'
      return
    }
    if (decoded.html) {
      singleHtml.value = decoded.html
      generateMode.value = 'single'
      return
    }
    singleHtml.value = '<pre style="padding:16px;white-space:pre-wrap">'
      + JSON.stringify(decoded, null, 2).replace(/</g, '&lt;')
      + '</pre>'
    generateMode.value = 'single'
    return
  }

  if (String(protoType).toUpperCase() === 'MULTI_PAGE' && typeof content === 'string') {
    try {
      const arr = JSON.parse(content)
      if (Array.isArray(arr) && arr.length > 0) {
        pages.value = arr.map((p: any, i: number) => ({
          title: p.title || `页面 ${p.order || i + 1}`,
          order: p.order || i + 1,
          html: p.html || '',
        }))
        pages.value.sort((a, b) => a.order - b.order)
        generateMode.value = 'multi'
        return
      }
    } catch { /* fall through */ }
  }

  singleHtml.value = typeof decoded === 'string' ? decoded : String(content)
  generateMode.value = 'single'
}

function selectPage(index: number) {
  if (index < 0 || index >= pages.value.length) return
  currentPageIndex.value = index
}

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

/** 从「我的文档」/ 路由参数进入时，加载对应原型预览 */
async function loadPrototypeById(id: number) {
  if (!id || Number.isNaN(id) || id <= 0) return
  if (openingPrototypeId.value === id || resultId.value === id) return
  openingPrototypeId.value = id
  opening.value = true
  progressMsg.value = '正在加载原型预览...'
  liveMessages.value = ['正在打开已保存的原型...']
  showPreview.value = false
  try {
    const protoRes = await client.get(`/prototype/${id}`)
    const data = protoRes.data?.data
    if (!data) throw new Error('原型数据为空')

    resultId.value = Number(data.id) || id
    if (data.platform) platform.value = String(data.platform)
    const pType = String(data.prototypeType || 'SINGLE_PAGE').toUpperCase()
    generateMode.value = pType === 'MULTI_PAGE' ? 'multi' : 'single'
    if (!description.value) {
      description.value = `原型 #${resultId.value}（${platform.value}）`
    }

    parseContent(data.content || '', pType)

    // 确保预览条件成立
    if (!singleHtml.value && pages.value.length === 0) {
      singleHtml.value = '<p style="padding:24px;color:#999">该原型内容为空</p>'
    }
    showPreview.value = true
  } catch (e: any) {
    showPreview.value = false
    ElMessage.error(e?.message || '加载原型失败')
  } finally {
    opening.value = false
    openingPrototypeId.value = null
  }
}

function resolveRoutePrototypeId(): number | null {
  const fromParams = Number(route.params.id)
  if (fromParams > 0) return fromParams
  const fromQuery = Number(route.query.id)
  if (fromQuery > 0) return fromQuery
  return null
}

// 进入页面或路由 id 变化时加载（immediate 覆盖首次进入）
watch(
  () => [route.params.id, route.query.id] as const,
  () => {
    const id = resolveRoutePrototypeId()
    if (id) loadPrototypeById(id)
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧面板 ====== -->
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">原型图生成</h2>
          <el-icon size="18" color="rgba(38,37,30,0.4)"><Clock /></el-icon>
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
          <el-input
            v-model="description"
            type="textarea"
            :rows="8"
            placeholder="描述你想要生成的页面功能..."
            maxlength="50000"
            show-word-limit
          />
        </div>

        <!-- ====== 风格参考图（可选） ====== -->
        <div class="form-group">
          <label class="form-label">
            风格参考图
            <span class="optional-tag">可选</span>
          </label>
          <input
            ref="imageInput"
            type="file"
            accept="image/jpeg,image/jpg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif"
            style="display:none"
            @change="onImageFileSelected"
          />

          <!-- 已上传：缩略图 + 文件名 + 删除 -->
          <div v-if="referenceImage" class="upload-zone upload-zone--done">
            <div class="ref-preview-wrap">
              <img
                v-if="referencePreviewUrl"
                :src="referencePreviewUrl"
                class="ref-thumb"
                alt="风格参考图预览"
              />
              <div class="ref-meta">
                <p class="upload-text">{{ referenceImage.name }}</p>
                <p class="upload-hint">{{ formatSize(referenceImage.size) }} · AI 将据此对齐视觉风格</p>
              </div>
            </div>
            <el-button
              class="btn-remove-file"
              size="small"
              text
              type="danger"
              @click="clearReferenceImage"
            >
              <el-icon style="margin-right:4px"><Delete /></el-icon>
              删除
            </el-button>
          </div>

          <!-- 未上传：虚线框 + 拖拽 -->
          <div
            v-else
            class="upload-zone"
            :class="{ 'upload-zone--drag': imageDragOver }"
            @click="triggerImageUpload"
            @dragover="onImageDragOver"
            @dragleave="onImageDragLeave"
            @drop="onImageDrop"
          >
            <el-icon size="40" color="rgba(38,37,30,0.2)"><UploadFilled /></el-icon>
            <p class="upload-text">点击上传参考图片</p>
            <p class="upload-hint">上传风格参考图，AI 将据此生成原型风格</p>
            <p class="upload-hint" style="margin-top:6px">支持 jpg / png / webp · 可拖拽到此处 · 最大 8MB</p>
          </div>
        </div>

        <el-button class="btn-generate" :loading="loading" :disabled="!canGenerate" @click="handleGenerate">一键生成</el-button>
      </div>

      <!-- ====== 右侧：预览 + 可视化编辑 ====== -->
      <div class="preview-panel">
        <template v-if="showPreview && resultId && (singleHtml || pages.length > 0)">
          <div v-if="isMultiPage" class="page-tabs">
            <div
              v-for="(page, idx) in pages" :key="idx"
              class="page-tab" :class="{ active: idx === currentPageIndex }"
              @click="selectPage(idx)"
            >
              <span class="tab-index">{{ idx + 1 }}</span>{{ page.title }}
            </div>
          </div>

          <PrototypeResultView
            :key="isMultiPage ? 'page-' + currentPageIndex : 'single'"
            v-model="currentHtml"
            :prototype-id="resultId"
            :platform="platform"
            :regenerating="loading"
            :page-titles="pageTitles"
            :page-index="currentPageIndex"
            class="result-view"
            @regenerate="handleGenerate"
            @goto-page="selectPage"
          />
        </template>

        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="rgba(38,37,30,0.15)"><PictureFilled /></el-icon>
            <p class="placeholder-title">交互原型即将呈现</p>
            <p class="placeholder-desc">在左侧描述功能需求并选择生成模式，AI 将生成可交互的 HTML 原型</p>
          </div>
        </template>

        <div v-if="loading || opening" class="preview-placeholder loading-mask generating-state">
          <el-icon class="loading-icon" size="54" color="#26251e"><Loading /></el-icon>
          <p class="generating-title">{{ opening ? '正在打开...' : '正在生成...' }}</p>
          <p class="generating-desc">{{ progressMsg || (opening ? '正在加载已保存的原型' : 'AI 正在生成原型，请稍候') }}</p>
          <div class="live-output">
            <div v-for="(msg, index) in liveMessages" :key="index" class="live-line">
              {{ msg }}
            </div>
          </div>
          <el-skeleton animated style="width:80%;max-width:500px">
            <template #template>
              <div style="display:flex;flex-direction:column;gap:16px;align-items:center">
                <el-skeleton-item variant="rect" style="width:100%;height:200px;border-radius:10px" />
                <el-skeleton-item variant="text" style="width:60%" />
                <el-skeleton-item variant="text" style="width:40%" />
              </div>
            </template>
          </el-skeleton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-container { padding: 24px 48px; height: 100%; box-sizing: border-box; }
.workspace { display: flex; height: 100%; border-radius: 10px; overflow: hidden; box-shadow: rgba(38, 37, 30, 0.1) 0px 0px 0px 1px; }
.config-panel { width: 420px; flex-shrink: 0; padding: 28px 24px; background: #f2f1ed; border-right: 1px solid rgba(38, 37, 30, 0.1); overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.module-title { font-size: 22px; font-weight: 400; color: #26251e; margin: 0; letter-spacing: -0.11px; }
.form-group { margin-bottom: 18px; }
.form-label { display: block; font-size: 14px; font-weight: 500; color: #26251e; margin-bottom: 8px; }
.required { color: #cf2d56; }
.optional-tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: rgba(38, 37, 30, 0.45);
  background: rgba(38, 37, 30, 0.06);
  padding: 1px 8px;
  border-radius: 999px;
}

.mode-btns { display: flex; gap: 4px; }
.mode-btn { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #e6e5e0; color: rgba(38, 37, 30, 0.55); transition: all .15s; user-select: none; border-radius: 8px; font-weight: 500; }
.mode-btn.active { background: #26251e; color: #f2f1ed; }
.mode-btn:hover:not(.active) { color: #cf2d56; }

.platform-cards { display: flex; gap: 10px; }
.platform-card { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 18px 8px; border: 1.5px solid rgba(38, 37, 30, 0.1); border-radius: 8px; cursor: pointer; background: #f7f7f4; color: rgba(38, 37, 30, 0.55); font-size: 13px; transition: all .15s; }
.platform-card:hover { border-color: #f54e00; color: #f54e00; }
.platform-card.active { border-color: #26251e; background: rgba(38, 37, 30, 0.05); color: #26251e; }

/* 上传区 —— 与 PRD 生成页一致 */
.upload-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 16px;
  border: 2px dashed rgba(38, 37, 30, 0.2);
  border-radius: 10px;
  background: #f7f7f4;
  cursor: pointer;
  transition: all .15s;
  min-height: 120px;
  user-select: none;
}
.upload-zone:hover { border-color: #f54e00; background: rgba(245, 78, 0, 0.03); }
.upload-zone--drag { border-color: #f54e00; background: rgba(245, 78, 0, 0.06); }
.upload-zone--done {
  min-height: 100px;
  border-style: solid;
  border-color: rgba(31, 138, 101, 0.35);
  background: rgba(31, 138, 101, 0.04);
  cursor: default;
  padding: 16px;
}
.upload-zone--done:hover { border-color: rgba(31, 138, 101, 0.5); background: rgba(31, 138, 101, 0.06); }
.upload-text { font-size: 14px; color: rgba(38, 37, 30, 0.75); margin: 12px 0 4px; word-break: break-all; text-align: center; padding: 0 8px; }
.upload-hint { font-size: 12px; color: rgba(38, 37, 30, 0.4); margin: 0; text-align: center; line-height: 1.5; }
.btn-remove-file { margin-top: 10px; }

.ref-preview-wrap {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
}
.ref-thumb {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid rgba(38, 37, 30, 0.1);
  background: #fff;
  flex-shrink: 0;
}
.ref-meta {
  flex: 1;
  min-width: 0;
  text-align: left;
}
.ref-meta .upload-text {
  margin: 0 0 4px;
  text-align: left;
  padding: 0;
  font-weight: 500;
  color: #26251e;
}
.ref-meta .upload-hint {
  text-align: left;
}

.btn-generate { width: 100%; height: 46px; font-size: 15px; font-weight: 400; border-radius: 8px; background: #e6e5e0 !important; border-color: transparent !important; color: rgba(38, 37, 30, 0.4) !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #26251e !important; border-color: #26251e !important; color: #f2f1ed !important; }
.btn-generate:not(:disabled):hover { opacity: 0.85; }

:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }

/* ====== 右侧预览区 ====== */
.preview-panel { flex: 1; min-width: 0; background: #f7f7f4; display: flex; flex-direction: column; position: relative; }
.result-view { flex: 1; min-height: 0; }

.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; flex: 1; }
.preview-placeholder.loading-mask { position: absolute; inset: 0; background: #f7f7f4; z-index: 5; }
.placeholder-title { font-size: 16px; color: rgba(38, 37, 30, 0.55); margin: 20px 0 8px; font-weight: 400; }
.placeholder-desc { font-size: 13px; color: rgba(38, 37, 30, 0.4); margin: 0; max-width: 320px; text-align: center; line-height: 1.7; }
.loading-icon { animation: spin 1s linear infinite; }
.generating-state .el-skeleton,
.generating-state > p:not(.generating-title):not(.generating-desc) { display: none; }
.generating-title { font-size: 17px; color: #26251e; margin: 18px 0 8px; font-weight: 500; }
.generating-desc { font-size: 13px; color: rgba(38, 37, 30, 0.58); margin: 0; text-align: center; line-height: 1.7; }
.live-output {
  width: min(520px, 82%);
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(38, 37, 30, 0.08);
}
.live-line { font-size: 13px; line-height: 1.7; color: rgba(38, 37, 30, 0.66); }
.live-line + .live-line { margin-top: 6px; }
@keyframes spin { to { transform: rotate(360deg); } }

.page-tabs {
  display: flex; gap: 6px; padding: 8px 12px; overflow-x: auto;
  background: #fff; border-bottom: 1px solid rgba(38, 37, 30, 0.08); flex-shrink: 0;
}
.page-tab {
  display: flex; align-items: center; gap: 6px; white-space: nowrap;
  padding: 5px 12px; border-radius: 7px; font-size: 12px; cursor: pointer;
  color: rgba(38, 37, 30, 0.6); background: #f2f1ed; transition: all .15s; user-select: none;
}
.page-tab:hover { color: #f54e00; }
.page-tab.active { background: #26251e; color: #f2f1ed; }
.tab-index {
  display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; border-radius: 50%; font-size: 11px;
  background: rgba(255, 255, 255, 0.18);
}
.page-tab:not(.active) .tab-index { background: rgba(38, 37, 30, 0.1); }
</style>
