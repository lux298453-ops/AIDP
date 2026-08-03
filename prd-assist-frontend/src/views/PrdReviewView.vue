<script setup lang="ts">
/**
 * PRD 审查页
 * 审查完成后：打开 PRD 编辑 / 导出 PRD / AI 修复（单条或严重+重要）
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, EditPen, MagicStick, Document, Clock, Checked, Loading } from '@element-plus/icons-vue'
import { getTaskSseUrl, getTaskById } from '@/api/task'
import client from '@/api/client'

const router = useRouter()
const route = useRoute()

// ==================== 输入 ====================
const inputMode = ref<'paste' | 'existing'>('paste')
const prdContent = ref('')
const prdDocumentId = ref<number | null>(null)
const existingPrds = ref<any[]>([])

// ==================== 审查维度 ====================
const dimensions = ref([
  { key: 'completeness', label: '功能完整性', desc: '遗漏场景、异常分支', icon: 'List', active: true },
  { key: 'consistency', label: '逻辑一致性', desc: '矛盾检查、术语统一', icon: 'Connection', active: true },
  { key: 'compliance', label: '合规性', desc: '安全要求、隐私标准', icon: 'Checked', active: true },
])
const requirement = ref('')
function toggleDim(item: { key: string; active: boolean }) { item.active = !item.active }

// ==================== 状态 ====================
const loading = ref(false)
const fixing = ref(false)
const fixingTarget = ref<'batch' | number | null>(null)
const progress = ref(0)
const progressMsg = ref('')
const liveMessages = ref<string[]>([])
const liveContent = ref('')
let taskId: number | null = null

interface Issue {
  severity: string
  dimension: string
  location: string
  description: string
  suggestion: string
}
const summary = ref('')
const score = ref(0)
const issues = ref<Issue[]>([])
const showReport = ref(false)
const reportId = ref<number | null>(null)
const linkedPrdId = ref<number | null>(null)

const severities = [
  { key: 'CRITICAL', label: '严重', color: '#cf2d56', bg: 'rgba(207,45,86,0.06)' },
  { key: 'MAJOR', label: '重要', color: '#c08532', bg: 'rgba(192,133,50,0.06)' },
  { key: 'MINOR', label: '轻微', color: 'rgba(38,37,30,0.55)', bg: 'rgba(38,37,30,0.04)' },
  { key: 'SUGGESTION', label: '建议', color: '#f54e00', bg: 'rgba(245,78,0,0.05)' },
]

const severityOrder: Record<string, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, SUGGESTION: 3 }
const sortedIssues = computed(() =>
  issues.value
    .map((issue, originalIndex) => ({ issue, originalIndex }))
    .sort((a, b) => (severityOrder[a.issue.severity] ?? 9) - (severityOrder[b.issue.severity] ?? 9)),
)
const issueCounts = computed(() => {
  const c: Record<string, number> = {}
  issues.value.forEach(i => { c[i.severity] = (c[i.severity] || 0) + 1 })
  return c
})
const criticalMajorCount = computed(() =>
  issues.value.filter(i => i.severity === 'CRITICAL' || i.severity === 'MAJOR').length,
)

const canReview = computed(() => {
  const hasDims = dimensions.value.some(d => d.active)
  return (inputMode.value === 'paste' ? prdContent.value.trim().length > 0 : !!prdDocumentId.value) && hasDims
})

// ==================== 提交审查 ====================
async function handleReview() {
  if (inputMode.value === 'paste' && !prdContent.value.trim()) {
    ElMessage.warning('请输入 PRD 内容'); return
  }
  const dims = dimensions.value.filter(d => d.active).map(d => d.key)
  if (dims.length === 0) { ElMessage.warning('请至少选择一个审查维度'); return }

  loading.value = true
  issues.value = []
  showReport.value = false
  summary.value = ''
  score.value = 0
  progress.value = 0
  liveContent.value = ''
  progressMsg.value = '正在提交审查任务...'
  liveMessages.value = ['正在提交审查任务...']
  reportId.value = null
  linkedPrdId.value = null
  taskId = null

  try {
    const body: Record<string, any> = { dimensions: dims }
    if (inputMode.value === 'paste') body.prdContent = prdContent.value
    else body.prdDocumentId = prdDocumentId.value
    if (requirement.value) body.requirement = requirement.value

    const res = await client.post('/prd/review', body)
    taskId = res.data.data.taskId
    startSse()
  } catch {
    loading.value = false
  }
}

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 审查服务...')
  const es = new EventSource(getTaskSseUrl(taskId))
  es.addEventListener('content', (e) => {
    handleContentEvent(e as MessageEvent)
  })
  es.addEventListener('progress', (e) => {
    const d = JSON.parse(e.data)
    progress.value = d.progress
    progressMsg.value = d.message
    appendLiveMessage(d.message)
    if (d.progress <= 0 && d.message && d.message.includes('失败')) {
      es.close(); loading.value = false; ElMessage.error(d.message)
      return
    }
    if (d.progress >= 100) {
      es.close(); loading.value = false; appendLiveMessage('审查完成，正在加载报告...'); ElMessage.success('审查完成'); fetchReport()
    }
  })
  es.onerror = () => { es.close(); loading.value = false }
}

function appendLiveMessage(message?: string) {
  const text = (message || '').trim()
  if (!text) return
  if (liveMessages.value[liveMessages.value.length - 1] === text) return
  liveMessages.value = [...liveMessages.value.slice(-5), text]
}

function finishFixing() {
  fixing.value = false
  fixingTarget.value = null
}

function handleContentEvent(event: MessageEvent) {
  try {
    const data = JSON.parse(event.data)
    if (data.snapshot) liveContent.value = data.delta || ''
    else liveContent.value += data.delta || ''
  } catch {
    // 流式内容只用于预览，解析失败不影响最终结果拉取。
  }
}

async function fetchReport() {
  if (!taskId) return
  const t = setInterval(async () => {
    try {
      const r = await getTaskById(taskId!)
      if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
        clearInterval(t)
        reportId.value = r.data.data.resultRefId
        const report = await client.get(`/review/${reportId.value}`)
        const data = report.data.data
        linkedPrdId.value = data.prdDocumentId && data.prdDocumentId > 0 ? data.prdDocumentId : null
        try {
          let parsed: any = data.issues || '{}'
          for (let i = 0; i < 3 && typeof parsed === 'string'; i++) parsed = JSON.parse(parsed)
          summary.value = parsed.summary || ''
          score.value = parsed.score || 0
          issues.value = parsed.issues || []
        } catch {
          issues.value = []
        }
        showReport.value = true
      }
      if (r.data.data.status === 'FAILED') {
        ElMessage.error(r.data.data.errorMessage || '审查失败')
        clearInterval(t)
      }
    } catch {
      clearInterval(t)
    }
  }, 1500)
}

// ==================== 报告动作 ====================
function openPrdEditor(issueIndex?: number) {
  if (!linkedPrdId.value) {
    ElMessage.warning('该报告未关联 PRD 文档')
    return
  }
  const query: Record<string, string> = { fromReview: String(reportId.value) }
  if (issueIndex != null) query.issue = String(issueIndex)
  router.push({ path: `/prd/${linkedPrdId.value}`, query })
}

async function exportReport() {
  if (!reportId.value) return
  try {
    const res = await client.get(`/review/${reportId.value}/export`, { responseType: 'blob' })
    downloadBlob(res.data, `审查报告_${reportId.value}.docx`)
    ElMessage.success('审查报告导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

async function exportPrd() {
  if (!reportId.value) return
  if (!linkedPrdId.value) {
    ElMessage.warning('未关联 PRD，无法导出')
    return
  }
  try {
    // 优先走 review 关联导出；也可用 /prd/{id}/export
    const res = await client.get(`/review/${reportId.value}/export-prd`, { responseType: 'blob' })
    downloadBlob(res.data, `PRD_${linkedPrdId.value}.docx`)
    ElMessage.success('PRD 导出成功')
  } catch {
    try {
      const res = await client.get(`/prd/${linkedPrdId.value}/export`, { responseType: 'blob' })
      downloadBlob(res.data, `PRD_${linkedPrdId.value}.docx`)
      ElMessage.success('PRD 导出成功')
    } catch {
      ElMessage.error('PRD 导出失败')
    }
  }
}

function downloadBlob(data: BlobPart, name: string) {
  const url = window.URL.createObjectURL(new Blob([data]))
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  window.URL.revokeObjectURL(url)
}

/** AI 修复：默认严重+重要；可传单条 index */
async function aiFix(issueIndexes?: number[]) {
  if (!reportId.value) return
  if (!linkedPrdId.value) {
    ElMessage.warning('未关联 PRD 文档，无法修复')
    return
  }

  const selectedIssueIndexes = resolveFixIssueIndexes(issueIndexes)
  const count = selectedIssueIndexes.length
  if (count === 0) {
    ElMessage.warning('没有可修复的严重/重要问题')
    return
  }

  try {
    await ElMessageBox.confirm(
      issueIndexes
        ? '将只修复该问题对应章节，并生成修订版 PRD（保留原版），是否继续？'
        : `将修复 ${count} 条严重/重要问题，生成新版 PRD（保留原版），是否继续？`,
      'AI 修订 PRD',
      { type: 'info', confirmButtonText: '开始修复', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  fixing.value = true
  fixingTarget.value = selectedIssueIndexes.length === 1 ? selectedIssueIndexes[0] : 'batch'
  progress.value = 0
  liveContent.value = ''
  progressMsg.value = selectedIssueIndexes.length === 1 ? '正在提交单条问题局部修复任务...' : '正在提交修复任务...'
  liveMessages.value = [progressMsg.value]
  try {
    const body = { issueIndexes: selectedIssueIndexes }
    const res = await client.post(`/review/${reportId.value}/fix`, body)
    const fixTaskId = res.data.data.taskId as number
    watchFixTask(fixTaskId, selectedIssueIndexes)
  } catch {
    finishFixing()
  }
}

function resolveFixIssueIndexes(issueIndexes?: number[]): number[] {
  if (issueIndexes?.length) return issueIndexes
  return issues.value
    .map((issue, index) => ({ issue, index }))
    .filter(({ issue }) => issue.severity === 'CRITICAL' || issue.severity === 'MAJOR')
    .map(({ index }) => index)
}

function buildFixedPrdQuery(fixedIssueIndexes: number[]) {
  const query: Record<string, string> = {
    compare: String(linkedPrdId.value),
    fromReview: String(reportId.value),
    fixedFromReview: String(reportId.value),
    fixedIssues: fixedIssueIndexes.join(','),
  }
  if (fixedIssueIndexes.length === 1) query.focusIssue = String(fixedIssueIndexes[0])
  return query
}

function watchFixTask(fixTaskId: number, fixedIssueIndexes: number[]) {
  appendLiveMessage('任务已创建，正在连接 AI 修复服务...')
  const es = new EventSource(getTaskSseUrl(fixTaskId))
  es.addEventListener('content', (e) => {
    handleContentEvent(e as MessageEvent)
  })
  es.addEventListener('progress', async (e) => {
    const d = JSON.parse((e as MessageEvent).data)
    progress.value = d.progress
    progressMsg.value = d.message
    appendLiveMessage(d.message)
    if (d.progress <= 0 && d.message && String(d.message).includes('失败')) {
      es.close(); finishFixing(); ElMessage.error(d.message)
      return
    }
    if (d.progress >= 100) {
      es.close()
      appendLiveMessage('修复完成，正在打开新版本...')
      try {
        const r = await getTaskById(fixTaskId)
        const newPrdId = r.data.data.resultRefId
        finishFixing()
        if (newPrdId) {
          ElMessage.success('修订完成，正在打开新版本')
          // 打开新版，并对比原版
          router.push({
            path: `/prd/${newPrdId}`,
            query: buildFixedPrdQuery(fixedIssueIndexes),
          })
        } else {
          ElMessage.warning('修复完成但未拿到文档 ID')
        }
      } catch {
        finishFixing()
      }
    }
  })
  es.onerror = async () => {
    es.close()
    try {
      const r = await getTaskById(fixTaskId)
      if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
        finishFixing()
        router.push({
          path: `/prd/${r.data.data.resultRefId}`,
          query: buildFixedPrdQuery(fixedIssueIndexes),
        })
      } else if (r.data.data.status === 'FAILED') {
        finishFixing()
        ElMessage.error(r.data.data.errorMessage || '修复失败')
      } else {
        finishFixing()
        ElMessage.warning('连接断开，请在我的文档中查看结果')
      }
    } catch {
      finishFixing()
    }
  }
}

async function loadExistingPrds() {
  try {
    const response = await client.get('/documents', { params: { taskType: 'PRD_GENERATE', page: 0, size: 100 } })
    existingPrds.value = response.data.data?.content || []
  } catch {
    existingPrds.value = []
  }
}

/** 从「我的文档」带 reportId 进来时，直接加载审查报告预览 */
async function loadReportById(id: number) {
  if (!id || Number.isNaN(id)) return
  loading.value = true
  progressMsg.value = '正在加载审查报告...'
  liveMessages.value = ['正在加载已保存的审查报告...']
  try {
    const report = await client.get(`/review/${id}`)
    const data = report.data.data
    reportId.value = data.id
    linkedPrdId.value = data.prdDocumentId && data.prdDocumentId > 0 ? data.prdDocumentId : null
    try {
      let parsed: any = data.issues || '{}'
      for (let i = 0; i < 3 && typeof parsed === 'string'; i++) parsed = JSON.parse(parsed)
      summary.value = parsed.summary || ''
      score.value = parsed.score || 0
      issues.value = parsed.issues || []
    } catch {
      summary.value = ''
      score.value = 0
      issues.value = []
    }
    showReport.value = true
    // 若报告关联了 PRD，左侧输入切到已有文档，方便继续操作
    if (linkedPrdId.value) {
      inputMode.value = 'existing'
      prdDocumentId.value = linkedPrdId.value
    }
    ElMessage.success('已打开审查报告')
  } catch {
    ElMessage.error('加载审查报告失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadExistingPrds()
  const qid = Number(route.query.prdDocumentId)
  if (qid) {
    inputMode.value = 'existing'
    prdDocumentId.value = qid
  }
  const rid = Number(route.query.reportId)
  if (rid) {
    await loadReportById(rid)
  }
})

function scoreColor(s: number) {
  if (s >= 80) return '#1f8a65'
  if (s >= 60) return '#c08532'
  return '#cf2d56'
}

function dimLabel(d: string) {
  const map: Record<string, string> = {
    completeness: '完整性',
    consistency: '一致性',
    compliance: '合规性',
  }
  return map[d] || d
}
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧面板 ====== -->
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">PRD 审查</h2>
          <el-icon size="18" color="rgba(38,37,30,0.4)"><Clock /></el-icon>
        </div>

        <div class="form-group">
          <label class="form-label">PRD 来源</label>
          <div class="mode-tabs">
            <div class="mode-tab" :class="{ active: inputMode === 'paste' }" @click="inputMode = 'paste'">直接粘贴</div>
            <div class="mode-tab" :class="{ active: inputMode === 'existing' }" @click="inputMode = 'existing'">选择已有PRD</div>
          </div>
        </div>
        <div v-if="inputMode === 'paste'" class="form-group">
          <el-input
            v-model="prdContent"
            type="textarea"
            :rows="10"
            placeholder="请粘贴需要审查的 PRD 文档内容（提交后会自动保存为文档，便于修改与导出）..."
            maxlength="50000"
            show-word-limit
          />
        </div>
        <div v-else class="form-group">
          <el-select v-model="prdDocumentId" clearable filterable placeholder="选择要审查的文档" style="width:100%">
            <el-option v-for="doc in existingPrds" :key="doc.id" :label="doc.title" :value="doc.id" />
          </el-select>
        </div>

        <div class="form-group">
          <label class="form-label">审查维度</label>
          <div class="dimension-cards">
            <div
              v-for="dim in dimensions"
              :key="dim.key"
              class="dimension-card"
              :class="{ active: dim.active }"
              @click="toggleDim(dim)"
            >
              <el-icon size="18"><component :is="dim.icon" /></el-icon>
              <span class="dim-label">{{ dim.label }}</span>
              <span class="dim-desc">{{ dim.desc }}</span>
            </div>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">自定义要求（可选）</label>
          <el-input v-model="requirement" type="textarea" :rows="2" placeholder="例如：请重点关注支付流程的安全性" maxlength="500" />
        </div>

        <el-button class="btn-generate" :loading="loading" :disabled="!canReview || fixing" @click="handleReview">
          开始审查
        </el-button>
      </div>

      <!-- ====== 右侧报告区 ====== -->
      <div class="report-panel">
        <template v-if="showReport && !loading">
          <div class="report-header">
            <div class="score-circle" :style="{ borderColor: scoreColor(score) }">
              <span class="score-num" :style="{ color: scoreColor(score) }">{{ score }}</span>
              <span class="score-label">分</span>
            </div>
            <div class="report-summary">
              <div class="report-title-row">
                <h3>审查报告</h3>
                <span v-if="linkedPrdId" class="linked-prd">已关联 PRD #{{ linkedPrdId }}</span>
              </div>
              <p>{{ summary }}</p>
              <div class="severity-tags">
                <span
                  v-for="s in severities"
                  :key="s.key"
                  v-show="issueCounts[s.key]"
                  class="sev-tag"
                  :style="{ background: s.bg, color: s.color }"
                >{{ s.label }} {{ issueCounts[s.key] }}</span>
              </div>

              <!-- 闭环动作 -->
              <div class="report-actions">
                <el-button type="primary" :disabled="!linkedPrdId" @click="openPrdEditor()">
                  <el-icon><EditPen /></el-icon>
                  打开 PRD 编辑
                </el-button>
                <el-button
                  :loading="fixingTarget === 'batch'"
                  :disabled="!linkedPrdId || criticalMajorCount === 0 || (fixing && fixingTarget !== 'batch')"
                  @click="aiFix()"
                >
                  <el-icon><MagicStick /></el-icon>
                  AI 修复严重项 ({{ criticalMajorCount }})
                </el-button>
                <el-button :disabled="!linkedPrdId" @click="exportPrd">
                  <el-icon><Document /></el-icon>
                  导出 PRD
                </el-button>
                <el-button @click="exportReport">
                  <el-icon><Download /></el-icon>
                  导出报告
                </el-button>
              </div>
            </div>
          </div>

          <div class="issues-list">
            <div v-for="item in sortedIssues" :key="item.originalIndex" class="issue-card">
              <div class="issue-head">
                <span
                  class="sev-badge"
                  :style="{
                    background: (severities.find(s => s.key === item.issue.severity) || {}).bg,
                    color: (severities.find(s => s.key === item.issue.severity) || {}).color,
                  }"
                >
                  {{ (severities.find(s => s.key === item.issue.severity) || {}).label || item.issue.severity }}
                </span>
                <span class="issue-dim">{{ dimLabel(item.issue.dimension) }}</span>
                <span class="issue-location" :title="item.issue.location">{{ item.issue.location }}</span>
              </div>
              <p class="issue-desc">{{ item.issue.description }}</p>
              <p class="issue-suggestion"><strong>建议：</strong>{{ item.issue.suggestion }}</p>
              <div class="issue-actions">
                <el-button size="small" text type="primary" :disabled="!linkedPrdId" @click="openPrdEditor(item.originalIndex)">
                  去编辑此章
                </el-button>
              </div>
            </div>
            <div v-if="issues.length === 0" class="empty-issues">未发现问题，文档质量良好。</div>
          </div>
        </template>

        <template v-else-if="!loading && !fixing">
          <div class="preview-placeholder">
            <el-icon size="60" color="rgba(38,37,30,0.15)"><Checked /></el-icon>
            <p class="placeholder-title">审查报告即将呈现</p>
            <p class="placeholder-desc">
              审查完成后可直接打开 PRD 编辑、AI 按问题修订，并导出修改后的文档
            </p>
          </div>
        </template>

        <div v-if="loading || fixing" class="preview-placeholder generating-state">
          <el-icon class="loading-icon" size="54" color="#26251e"><Loading /></el-icon>
          <p class="generating-title">正在生成...</p>
          <p class="generating-desc">{{ progressMsg || (fixing ? 'AI 正在修复 PRD，请稍候' : 'AI 正在审查 PRD，请稍候') }}</p>
          <div class="live-output">
            <div v-for="(msg, index) in liveMessages" :key="index" class="live-line">
              {{ msg }}
            </div>
          </div>
          <pre v-if="liveContent" class="stream-preview">{{ liveContent }}</pre>
          <el-skeleton animated style="width:80%;max-width:500px">
            <template #template>
              <div style="display:flex;flex-direction:column;gap:16px;align-items:center">
                <el-skeleton-item variant="rect" style="width:100%;height:160px;border-radius:10px" />
                <el-skeleton-item variant="text" style="width:60%" />
                <el-skeleton-item variant="text" style="width:40%" />
              </div>
            </template>
          </el-skeleton>
          <p style="color:#909399;font-size:14px;margin-top:20px">
            {{ progressMsg || (fixing ? 'AI 正在修订 PRD...' : 'AI 正在审查 PRD，请稍候...') }}
          </p>
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
.progress-msg { color: rgba(38,37,30,0.55); font-size: 13px; margin-top: 6px; }
.stream-preview { width: min(760px, 92%); max-height: 280px; overflow: auto; white-space: pre-wrap; text-align: left; padding: 14px 16px; border-radius: 8px; background: rgba(255,255,255,0.72); border: 1px solid rgba(38,37,30,0.1); color: #26251e; font-size: 13px; line-height: 1.7; }

.mode-tabs { display: flex; gap: 4px; }
.mode-tab { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #e6e5e0; color: rgba(38, 37, 30, 0.55); transition: all .15s; user-select: none; border-radius: 8px; font-weight: 500; }
.mode-tab.active { background: #26251e; color: #f2f1ed; }
.mode-tab:hover:not(.active) { color: #cf2d56; }

.dimension-cards { display: flex; flex-direction: column; gap: 8px; }
.dimension-card { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border: 1.5px solid rgba(38, 37, 30, 0.1); border-radius: 8px; cursor: pointer; background: #f7f7f4; transition: all .15s; }
.dimension-card.active { border-color: #26251e; background: rgba(38, 37, 30, 0.05); }
.dimension-card:hover:not(.active) { border-color: #f54e00; }
.dimension-card .dim-label { font-size: 14px; font-weight: 500; color: #26251e; flex-shrink: 0; }
.dimension-card .dim-desc { font-size: 12px; color: rgba(38, 37, 30, 0.55); }

.btn-generate { width: 100%; height: 46px; font-size: 15px; font-weight: 400; border-radius: 8px; background: #e6e5e0 !important; border-color: transparent !important; color: rgba(38, 37, 30, 0.4) !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #26251e !important; border-color: #26251e !important; color: #f2f1ed !important; }
.btn-generate:not(:disabled):hover { opacity: 0.85; }

:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }

.report-panel { flex: 1; background: #f7f7f4; overflow-y: auto; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; min-height: 400px; }
.placeholder-title { font-size: 16px; color: rgba(38, 37, 30, 0.55); margin: 20px 0 8px; font-weight: 400; }
.placeholder-desc { font-size: 13px; color: rgba(38, 37, 30, 0.4); margin: 0; max-width: 360px; text-align: center; line-height: 1.7; }
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

.report-header { display: flex; gap: 24px; padding: 28px 32px; background: #fff; border-bottom: 1px solid rgba(38, 37, 30, 0.1); align-items: flex-start; }
.score-circle { width: 80px; height: 80px; border-radius: 50%; border: 4px solid; display: flex; flex-direction: column; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 4px; }
.score-num { font-size: 28px; font-weight: 400; line-height: 1; letter-spacing: -0.5px; }
.score-label { font-size: 12px; color: rgba(38, 37, 30, 0.4); }
.report-summary { flex: 1; min-width: 0; }
.report-title-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.report-summary h3 { margin: 0; font-size: 18px; font-weight: 400; color: #26251e; }
.linked-prd { font-size: 12px; color: #1f8a65; background: rgba(31,138,101,0.1); padding: 2px 10px; border-radius: 999px; }
.report-summary > p { margin: 0 0 10px; font-size: 14px; color: rgba(38, 37, 30, 0.75); line-height: 1.6; }
.severity-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 14px; }
.sev-tag { font-size: 12px; padding: 2px 10px; border-radius: 9999px; font-weight: 500; }

.report-actions { display: flex; flex-wrap: wrap; gap: 8px; }

.issues-list { padding: 20px 32px 40px; display: flex; flex-direction: column; gap: 14px; }
.issue-card { background: #fff; border-radius: 8px; padding: 18px 20px; border: 1px solid rgba(38, 37, 30, 0.1); }
.issue-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.sev-badge { font-size: 12px; font-weight: 500; padding: 2px 10px; border-radius: 9999px; }
.issue-dim { font-size: 13px; color: rgba(38, 37, 30, 0.55); }
.issue-location { font-size: 13px; color: #f54e00; margin-left: auto; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.issue-desc { font-size: 14px; color: #26251e; margin: 0 0 8px; line-height: 1.6; }
.issue-suggestion { font-size: 13px; color: #1f8a65; margin: 0 0 10px; line-height: 1.5; background: rgba(31, 138, 101, 0.06); padding: 8px 12px; border-radius: 6px; }
.issue-actions { display: flex; gap: 4px; border-top: 1px solid rgba(38,37,30,0.06); padding-top: 8px; }
.empty-issues { text-align: center; color: rgba(38,37,30,0.45); padding: 40px; font-size: 14px; }

@media (max-width: 900px) {
  .workspace { flex-direction: column; }
  .config-panel { width: 100%; }
  .report-header { flex-direction: column; align-items: center; text-align: center; }
  .issue-location { margin-left: 0; max-width: 100%; }
}
</style>
