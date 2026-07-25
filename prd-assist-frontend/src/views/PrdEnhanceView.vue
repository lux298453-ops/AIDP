<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTask, getTaskSseUrl, getTaskById } from '@/api/task'
import client from '@/api/client'

// ==================== 输入来源 ====================
const inputMode = ref<'paste' | 'existing'>('paste')
const router = useRouter()
const route = useRoute()
const prdContent = ref('')
const prdDocumentId = ref<number | null>(null)
const existingPrds = ref<any[]>([])

// ==================== Word 上传 ====================
const wordInput = ref<HTMLInputElement | null>(null)
const wordFile = ref<File | null>(null)

function triggerWordUpload() { wordInput.value?.click() }
function onWordFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    const f = input.files[0]
    if (!f.name.toLowerCase().endsWith('.doc') && !f.name.toLowerCase().endsWith('.docx')) {
      ElMessage.warning('仅支持 .doc / .docx 文件'); return
    }
    wordFile.value = f; ElMessage.success(`已选择: ${f.name}`)
  }
}

// ==================== 内容类型（全部默认选中） ====================
const contentTypes = ref([
  { key: 'structure', label: '页面结构图', icon: 'Grid', active: true },
  { key: 'flow', label: '流程图', icon: 'Share', active: true },
  { key: 'data', label: '数据字段', icon: 'Collection', active: true },
  { key: 'testcase', label: '测试用例', icon: 'List', active: true },
])

function toggleType(item: { key: string; active: boolean }) { item.active = !item.active }

// ==================== 自定义指令 ====================
const instruction = ref('')

// ==================== 状态 ====================
const loading = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const liveMessages = ref<string[]>([])
const result = ref('')
let taskId: number | null = null

const canSubmit = computed(() => {
  const hasTypes = contentTypes.value.some(t => t.active)
  const hasSource = inputMode.value === 'existing' ? !!prdDocumentId.value : prdContent.value.trim().length > 0
  return (hasSource || !!wordFile.value) && hasTypes
})

// ==================== 提交 ====================
async function handleEnhance() {
  const selectedTypes = contentTypes.value.filter(t => t.active).map(t => t.key)
  if (selectedTypes.length === 0) { ElMessage.warning('请至少选择一种内容类型'); return }
  if (inputMode.value === 'paste' && !prdContent.value.trim()) { ElMessage.warning('请输入 PRD 内容'); return }

  loading.value = true
  result.value = ''
  progress.value = 0
  progressMsg.value = '正在提交增强任务...'
  liveMessages.value = ['正在提交增强任务...']

  try {
    if (wordFile.value) {
      // Word 上传模式
      const fd = new FormData(); fd.append('file', wordFile.value)
      if (prdContent.value) fd.append('prdContent', prdContent.value)
      if (prdDocumentId.value) fd.append('prdDocumentId', String(prdDocumentId.value))
      fd.append('contentTypes', selectedTypes.join(','))
      if (instruction.value) fd.append('instruction', instruction.value)

      const res = await client.post('/prd/enhance/word', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
      taskId = res.data.data.taskId
    } else {
      // 纯文本模式
      const res = await client.post('/prd/enhance', {
        prdContent: prdContent.value, prdDocumentId: prdDocumentId.value,
        contentTypes: selectedTypes, instruction: instruction.value,
      })
      taskId = res.data.data.taskId
    }
    startSse()
  } catch { loading.value = false }
}

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 增强服务...')
  const es = new EventSource(getTaskSseUrl(taskId))
  es.addEventListener('progress', (e) => {
    const d = JSON.parse(e.data); progress.value = d.progress; progressMsg.value = d.message
    appendLiveMessage(d.message)
    if (d.progress >= 100) { es.close(); loading.value = false; ElMessage.success('增强完成'); pollResult() }
  })
  es.onerror = () => { es.close(); loading.value = false }
}

function appendLiveMessage(message?: string) {
  const text = (message || '').trim()
  if (!text) return
  if (liveMessages.value[liveMessages.value.length - 1] === text) return
  liveMessages.value = [...liveMessages.value.slice(-5), text]
}

async function pollResult() {
  if (!taskId) return
  const t = setInterval(async () => {
    const r = await getTaskById(taskId!)
    if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
      clearInterval(t)
      const source = prdDocumentId.value
      await router.push({ path: `/prd/${r.data.data.resultRefId}`, query: source ? { compare: String(source) } : undefined })
    }
    if (r.data.data.status === 'FAILED') { ElMessage.error(r.data.data.errorMessage || '失败'); clearInterval(t) }
  }, 2000)
}

async function loadExistingPrds() {
  try {
    const response = await client.get('/documents', { params: { taskType: 'PRD_GENERATE', page: 0, size: 100 } })
    existingPrds.value = response.data.data?.content || []
  } catch { existingPrds.value = [] }
}

onMounted(async () => {
  await loadExistingPrds()
  // 从 PRD 结果页「用作增强」带入文档 ID
  const qid = Number(route.query.prdDocumentId)
  if (qid) {
    inputMode.value = 'existing'
    prdDocumentId.value = qid
  }
})
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧面板 ====== -->
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">PRD增强</h2>
          <el-icon size="18" color="rgba(38,37,30,0.4)"><Clock /></el-icon>
        </div>

        <!-- 输入模式 -->
        <div class="form-group">
          <label class="form-label">PRD 来源</label>
          <div class="mode-tabs">
            <div class="mode-tab" :class="{ active: inputMode === 'paste' }" @click="inputMode = 'paste'">直接粘贴</div>
            <div class="mode-tab" :class="{ active: inputMode === 'existing' }" @click="inputMode = 'existing'">选择已有PRD</div>
          </div>
        </div>

        <!-- 粘贴模式 -->
        <div v-if="inputMode === 'paste'" class="form-group">
          <label class="form-label">PRD 内容</label>
          <el-input v-model="prdContent" type="textarea" :rows="8" placeholder="请粘贴已有的 PRD 文档内容..." maxlength="50000" show-word-limit />
        </div>
        <div v-else class="form-group">
          <label class="form-label">选择已有 PRD</label>
          <el-select v-model="prdDocumentId" clearable filterable placeholder="选择要增强的文档" style="width:100%">
            <el-option v-for="doc in existingPrds" :key="doc.id" :label="doc.title" :value="doc.id" />
          </el-select>
        </div>

        <!-- 内容类型 -->
        <div class="form-group">
          <label class="form-label">内容包含</label>
          <div class="content-cards">
            <div v-for="item in contentTypes" :key="item.key" class="content-card" :class="{ active: item.active }" @click="toggleType(item)">
              <el-icon size="15"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </div>
          </div>
        </div>

        <!-- Word 素材上传 -->
        <div class="form-group">
          <label class="form-label">PRD素材 示例</label>
          <input ref="wordInput" type="file" accept=".doc,.docx" style="display:none" @change="onWordFileSelected" />
          <div class="upload-dashed" @click="triggerWordUpload">
            <el-icon size="22" :color="wordFile ? '#1f8a65' : 'rgba(38,37,30,0.4)'"><Upload /></el-icon>
            <span>{{ wordFile ? wordFile.name : '点击上传word素材（doc格式）' }}</span>
          </div>
        </div>

        <!-- 自定义指令 -->
        <div class="form-group">
          <label class="form-label">自定义指令（可选）</label>
          <el-input v-model="instruction" type="textarea" :rows="2" placeholder="例如：重点补充移动端的适配方案" maxlength="500" />
        </div>

        <!-- 一键生成 -->
        <el-button class="btn-generate" :loading="loading" :disabled="!canSubmit" @click="handleEnhance">一键生成</el-button>
      </div>

      <!-- ====== 右侧预览区 ====== -->
      <div class="preview-panel">
        <template v-if="result">
          <div class="result-content" v-html="result.replace(/\n/g, '<br>')" />
        </template>
        <template v-else-if="!loading">
          <div class="preview-placeholder">
            <el-icon size="60" color="rgba(38,37,30,0.15)"><Document /></el-icon>
            <p class="placeholder-title">增强结果即将呈现</p>
            <p class="placeholder-desc">勾选「页面结构图 / 流程图」后，AI 将生成可渲染的 Mermaid 图表并写入增强版 PRD</p>
          </div>
        </template>
        <div v-if="loading" class="preview-placeholder generating-state">
          <el-icon class="loading-icon" size="54" color="#26251e"><Loading /></el-icon>
          <p class="generating-title">正在生成...</p>
          <p class="generating-desc">{{ progressMsg || 'AI 正在增强 PRD，请稍候' }}</p>
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
          <p style="color:#909399;font-size:14px;margin-top:20px">{{ progressMsg || 'AI 正在增强 PRD，请稍候…' }}</p>
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

/* 模式切换 */
.mode-tabs { display: flex; gap: 4px; }
.mode-tab { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #e6e5e0; color: rgba(38, 37, 30, 0.55); transition: all .15s; user-select: none; border-radius: 8px; font-weight: 500; }
.mode-tab.active { background: #26251e; color: #f2f1ed; }
.mode-tab:hover:not(.active) { color: #cf2d56; }

/* 内容卡片 */
.content-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.content-card { display: flex; flex-direction: row; align-items: center; justify-content: center; gap: 6px; height: 34px; padding: 0 10px; border: 1px solid rgba(38, 37, 30, 0.15); border-radius: 7px; cursor: pointer; background: #f7f7f4; color: rgba(38, 37, 30, 0.6); font-size: 13px; line-height: 1; transition: all .15s; user-select: none; }
.content-card .el-icon { flex-shrink: 0; }
.content-card.active { border-color: #26251e; background: rgba(38, 37, 30, 0.05); color: #26251e; font-weight: 500; }
.content-card:hover:not(.active) { border-color: #f54e00; color: #f54e00; }

/* 虚线框上传 */
.upload-dashed { display: flex; align-items: center; gap: 10px; padding: 16px 18px; border: 1.5px dashed rgba(38, 37, 30, 0.2); border-radius: 8px; color: rgba(38, 37, 30, 0.55); font-size: 14px; cursor: pointer; background: #f7f7f4; transition: border-color .15s; }
.upload-dashed:hover { border-color: #f54e00; }

/* 按钮 */
.btn-generate { width: 100%; height: 46px; font-size: 15px; font-weight: 400; border-radius: 8px; background: #e6e5e0 !important; border-color: transparent !important; color: rgba(38, 37, 30, 0.4) !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #26251e !important; border-color: #26251e !important; color: #f2f1ed !important; }
.btn-generate:not(:disabled):hover { opacity: 0.85; }

:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }

/* 右侧预览 */
.preview-panel { flex: 1; background: #f7f7f4; padding: 40px; overflow-y: auto; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; min-height: 400px; }
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
.result-content { line-height: 1.8; color: #26251e; font-size: 14px; }
</style>
