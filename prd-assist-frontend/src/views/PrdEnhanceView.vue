<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import ThinkingStatus from '@/components/common/ThinkingStatus.vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Document,
  Upload,
  Clock,
  MagicStick,
  Grid,
  Share,
  Collection,
  List,
  Check,
} from '@element-plus/icons-vue'
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
  { key: 'structure', label: '页面结构图', icon: Grid, active: true },
  { key: 'flow', label: '业务流程图', icon: Share, active: true },
  { key: 'data', label: '数据字典', icon: Collection, active: true },
  { key: 'testcase', label: '核心测试用例', icon: List, active: true },
])

function toggleType(item: { key: string; active: boolean }) { item.active = !item.active }

// ==================== 自定义指令 ====================
const instruction = ref('')

// ==================== 状态 ====================
const loading = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const liveMessages = ref<string[]>([])
const liveContent = ref('')
const result = ref('')
const renderedResult = computed(() => escapeHtml(result.value).replace(/\n/g, '<br>'))

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
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
  liveContent.value = ''
  progress.value = 0
  progressMsg.value = '正在提交增强任务...'
  liveMessages.value = ['正在提交增强任务...']

  try {
    if (wordFile.value) {
      const fd = new FormData(); fd.append('file', wordFile.value)
      if (prdContent.value) fd.append('prdContent', prdContent.value)
      if (prdDocumentId.value) fd.append('prdDocumentId', String(prdDocumentId.value))
      fd.append('contentTypes', selectedTypes.join(','))
      if (instruction.value) fd.append('instruction', instruction.value)

      const res = await client.post('/prd/enhance/word', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
      taskId = res.data.data.taskId
    } else {
      const res = await client.post('/prd/enhance', {
        prdContent: prdContent.value, prdDocumentId: prdDocumentId.value,
        contentTypes: selectedTypes, instruction: instruction.value,
      })
      taskId = res.data.data.taskId
    }
    startSse()
  } catch { loading.value = false }
}

let activeEventSource: EventSource | null = null

onBeforeUnmount(() => {
  activeEventSource?.close()
  activeEventSource = null
})

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 增强服务...')
  activeEventSource?.close()
  const eventSource = new EventSource(getTaskSseUrl(taskId))
  activeEventSource = eventSource
  eventSource.addEventListener('content', (event) => {
    try {
      const data = JSON.parse((event as MessageEvent).data)
      if (data.snapshot) liveContent.value = data.delta || ''
      else liveContent.value += data.delta || ''
      result.value = liveContent.value
    } catch { /* ignore */ }
  })
  eventSource.addEventListener('progress', async (event) => {
    const data = JSON.parse((event as MessageEvent).data)
    progress.value = data.progress
    progressMsg.value = data.message
    appendLiveMessage(data.message)
    if (data.progress <= 0 && data.message && data.message.includes('失败')) {
      eventSource.close(); loading.value = false
      ElMessage.error(data.message)
      return
    }
    if (data.progress >= 100) {
      eventSource.close(); loading.value = false
      appendLiveMessage('增强完成，正在打开增强版 PRD...')
      ElMessage.success('PRD 增强完成')
      try {
        const r = await getTaskById(taskId!)
        const refId = r.data.data.resultRefId
        if (refId) {
          const compareId = inputMode.value === 'existing' && prdDocumentId.value ? String(prdDocumentId.value) : ''
          await router.push({ path: `/prd/${refId}`, query: compareId ? { compare: compareId } : {} })
        }
      } catch { /* ignore */ }
    }
  })
  eventSource.onerror = async () => {
    eventSource.close()
    try {
      const r = await getTaskById(taskId!)
      if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
        loading.value = false
        const compareId = inputMode.value === 'existing' && prdDocumentId.value ? String(prdDocumentId.value) : ''
        await router.push({ path: `/prd/${r.data.data.resultRefId}`, query: compareId ? { compare: compareId } : {} })
      } else if (r.data.data.status === 'FAILED') {
        loading.value = false; ElMessage.error(r.data.data.errorMessage || '增强失败')
      } else {
        loading.value = false; ElMessage.warning('进度连接已断开，请在我的文档中查看任务结果')
      }
    } catch { loading.value = false }
  }
}

function appendLiveMessage(message?: string) {
  const text = (message || '').trim()
  if (!text) return
  if (liveMessages.value[liveMessages.value.length - 1] === text) return
  liveMessages.value = [...liveMessages.value.slice(-5), text]
}

async function loadExistingPrds() {
  try {
    const response = await client.get('/documents', { params: { taskType: 'PRD_GENERATE', page: 0, size: 100 } })
    existingPrds.value = response.data.data?.content || []
  } catch { existingPrds.value = [] }
}

onMounted(async () => {
  await loadExistingPrds()
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
      <aside class="config-panel">
        <div class="panel-header">
          <div>
            <h2 class="module-title">PRD 增强</h2>
            <p class="module-subtitle">推导架构图、流程与数据字典</p>
          </div>
          <div class="panel-badge" title="Mermaid 架构与流程图表">
            <el-icon :size="15"><Share /></el-icon>
          </div>
        </div>

        <!-- 来源模式切换 -->
        <div class="mode-tabs">
          <button
            type="button"
            class="mode-tab"
            :class="{ active: inputMode === 'paste' }"
            @click="inputMode = 'paste'"
          >
            直接粘贴 PRD
          </button>
          <button
            type="button"
            class="mode-tab"
            :class="{ active: inputMode === 'existing' }"
            @click="inputMode = 'existing'"
          >
            选择已有文档
          </button>
        </div>

        <!-- 粘贴内容 / 下拉选择 -->
        <div v-if="inputMode === 'paste'" class="form-group">
          <label class="form-label">
            PRD 原始内容 <span class="required">*</span>
          </label>
          <el-input
            v-model="prdContent"
            type="textarea"
            :rows="8"
            placeholder="粘贴需要补充图表和字段的已有 PRD 正文..."
            maxlength="50000"
            show-word-limit
          />
        </div>
        <div v-else class="form-group">
          <label class="form-label">选择要增强的 PRD <span class="required">*</span></label>
          <el-select v-model="prdDocumentId" clearable filterable placeholder="从我的文档库中选择" style="width:100%">
            <el-option v-for="doc in existingPrds" :key="doc.id" :label="doc.title" :value="doc.id" />
          </el-select>
        </div>

        <!-- 增强内容类型（四宫格 Chips） -->
        <div class="form-group">
          <label class="form-label">包含增强维度</label>
          <div class="content-cards-grid">
            <button
              v-for="item in contentTypes"
              :key="item.key"
              type="button"
              class="content-chip"
              :class="{ active: item.active }"
              @click="toggleType(item)"
            >
              <el-icon :size="14"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
              <el-icon v-if="item.active" class="check-icon" :size="12"><Check /></el-icon>
            </button>
          </div>
        </div>

        <!-- Word 素材附件 -->
        <div class="form-group">
          <label class="form-label">补充 Word 素材 (可选)</label>
          <input ref="wordInput" type="file" accept=".doc,.docx" style="display:none" @change="onWordFileSelected" />
          <div class="upload-zone upload-zone--compact" :class="{ 'upload-zone--done': !!wordFile }" @click="triggerWordUpload">
            <el-icon :size="24" :color="wordFile ? '#059669' : '#94a3b8'"><Upload /></el-icon>
            <p class="upload-text">{{ wordFile ? wordFile.name : '点击上传 .docx 素材作为参考' }}</p>
            <p class="upload-hint">{{ wordFile ? `${(wordFile.size / 1024).toFixed(1)} KB · 点击可替换` : 'AI 将抽取背景与术语进行融合' }}</p>
          </div>
        </div>

        <!-- 自定义指令 -->
        <div class="form-group">
          <label class="form-label">定制指令 (可选)</label>
          <el-input v-model="instruction" type="textarea" :rows="2" placeholder="例如：流程图中重点突出多角色驳回分支；数据字典增加字段长度说明" maxlength="500" />
        </div>

        <!-- 一键生成按钮 -->
        <div class="generate-btn-wrap">
          <button
            type="button"
            class="btn-generate"
            :disabled="!canSubmit || loading"
            @click="handleEnhance"
          >
            <el-icon v-if="!loading" :size="16"><MagicStick /></el-icon>
            <span>{{ loading ? 'AI 正在推导并增强 PRD...' : '一键增强 PRD' }}</span>
          </button>
        </div>
      </aside>

      <!-- ====== 右侧主舞台区 ====== -->
      <main class="preview-panel">
        <template v-if="loading">
          <div class="generating-container">
            <div class="status-box">
              <ThinkingStatus :steps="liveMessages" />
            </div>
            <div class="generating-meta">
              <span class="pulse-indicator" />
              <span>{{ progressMsg || 'AI 正在绘制架构与流程图，即将完成...' }}</span>
            </div>
          </div>
        </template>

        <template v-else-if="result">
          <div class="result-card">
            <div class="result-header">
              <span class="result-badge">增强结果草稿</span>
              <span class="result-hint">完成生成后将自动跳转至带图表渲染的编辑器</span>
            </div>
            <div class="result-content" v-html="renderedResult" />
          </div>
        </template>

        <template v-else>
          <div class="onboarding-container">
            <div class="empty-icon-wrap">
              <el-icon :size="32" color="#0f172a"><Share /></el-icon>
            </div>
            <h3 class="empty-title">自动推导 Mermaid 架构图与用例</h3>
            <p class="empty-desc">
              粘贴已有 PRD 粗稿，AI 将自动分析页面模块关系并生成交互结构图，梳理前后置业务流，补齐数据表字典。
            </p>

            <div class="feature-cards-row">
              <div class="feature-card">
                <el-icon :size="18" color="#f97316"><Grid /></el-icon>
                <div class="f-text">
                  <strong>页面结构图</strong>
                  <span>模块树状展开与导航拓扑</span>
                </div>
              </div>
              <div class="feature-card">
                <el-icon :size="18" color="#059669"><Share /></el-icon>
                <div class="f-text">
                  <strong>业务流程图</strong>
                  <span>泳道流转与条件分支判断</span>
                </div>
              </div>
              <div class="feature-card">
                <el-icon :size="18" color="#3b82f6"><Collection /></el-icon>
                <div class="f-text">
                  <strong>字段字典</strong>
                  <span>类型、必填校验与约束</span>
                </div>
              </div>
            </div>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.page-container {
  padding: 24px 32px;
  height: 100%;
  box-sizing: border-box;
}
.workspace {
  display: flex;
  height: 100%;
  background: #ffffff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08), 0 2px 4px rgba(15, 23, 42, 0.03);
}

/* 侧栏 */
.config-panel {
  width: 380px;
  flex-shrink: 0;
  padding: 22px 20px;
  background: #ffffff;
  border-right: 1px solid #f1f5f9;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.module-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  margin: 0 0 2px;
  letter-spacing: -0.02em;
}
.module-subtitle {
  font-size: 12px;
  color: #94a3b8;
  margin: 0;
}
.panel-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background: #f8fafc;
  color: #0284c7;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
  transition: all 0.15s ease;
}

.mode-tabs {
  display: flex;
  background: #f1f5f9;
  padding: 3px;
  border: 1px solid rgba(226, 232, 240, 0.8);
  border-radius: 8px;
  gap: 2px;
}
.mode-tab {
  flex: 1;
  text-align: center;
  padding: 6px 0;
  font-size: 12.5px;
  cursor: pointer;
  background: transparent;
  color: #64748b;
  border: none;
  border-radius: 6px;
  font-weight: 500;
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}
.mode-tab:hover:not(.active) { color: #0f172a; background: rgba(255, 255, 255, 0.5); }
.mode-tab.active {
  background: #ffffff;
  color: #0f172a;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08), 0 0 0 1px rgba(15, 23, 42, 0.04);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-label {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}
.required { color: #dc2626; }

/* 四宫格 Chips */
.content-cards-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.content-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 11px;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  background: #ffffff;
  color: #475569;
  font-size: 12px;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}
.content-chip:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #0f172a;
}
.content-chip.active {
  background: #eff6ff;
  color: #1d4ed8;
  border-color: #93c5fd;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px #bfdbfe;
}
.check-icon {
  margin-left: 4px;
  color: #2563eb;
}

/* 上传框 */
.upload-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px 14px;
  border: 1.5px dashed #cbd5e1;
  border-radius: 10px;
  background: #f8fafc;
  cursor: pointer;
  transition: all 0.15s ease;
}
.upload-zone:hover {
  border-color: #2563eb;
  background: #eff6ff;
}
.upload-zone--done {
  border-style: solid;
  border-color: #a7f3d0;
  background: #f0fdf4;
}
.upload-text {
  font-size: 12.5px;
  font-weight: 500;
  color: #0f172a;
  margin: 6px 0 2px;
  text-align: center;
}
.upload-hint {
  font-size: 11px;
  color: #94a3b8;
  margin: 0;
  text-align: center;
}

.generate-btn-wrap {
  margin-top: auto;
  padding-top: 12px;
}
.btn-generate {
  width: 100%;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 7px;
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%);
  color: #ffffff;
  border: 1px solid #1d4ed8;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  cursor: pointer;
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.25), 0 1px 2px 0 rgba(15, 23, 42, 0.08);
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}
.btn-generate:hover:not(:disabled) {
  background: linear-gradient(180deg, #2563eb 0%, #1d4ed8 100%);
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.2), 0 2px 5px 0 rgba(37, 99, 235, 0.25);
}
.btn-generate:active:not(:disabled) {
  transform: translateY(1px);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.2);
}
.btn-generate:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
  background: #94a3b8;
  border-color: #94a3b8;
}

.preview-panel {
  flex: 1;
  background: #f8fafc;
  padding: 32px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.onboarding-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: auto;
  max-width: 580px;
  text-align: center;
  padding: 24px 0;
}
.empty-icon-wrap {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.04);
}
.empty-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}
.empty-desc {
  margin: 0 0 28px;
  font-size: 13.5px;
  color: #64748b;
  line-height: 1.6;
}
.feature-cards-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
}
.feature-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  text-align: left;
}
.f-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.f-text strong {
  font-size: 13px;
  color: #0f172a;
  font-weight: 600;
}
.f-text span {
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.4;
}

.generating-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: auto;
  width: 100%;
  max-width: 580px;
}
.status-box { width: 100%; }
.generating-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  font-size: 12.5px;
  color: #64748b;
}
.pulse-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f97316;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(249, 115, 22, 0.6); }
  70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(249, 115, 22, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(249, 115, 22, 0); }
}

.result-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}
.result-badge {
  font-size: 12px;
  font-weight: 600;
  color: #059669;
  background: #ecfdf5;
  padding: 2px 8px;
  border-radius: 4px;
}
.result-hint { font-size: 12px; color: #94a3b8; }
.result-content { line-height: 1.8; color: #1e293b; font-size: 14px; }

@media (max-width: 1024px) {
  .page-container { padding: 16px; height: auto; min-height: 100%; }
  .workspace { flex-direction: column; height: auto; }
  .config-panel { width: 100%; border-right: none; border-bottom: 1px solid #e2e8f0; }
  .feature-cards-row { grid-template-columns: 1fr; }
}
</style>
