<script setup lang="ts">
/**
 * PRD 审查页
 * 审查完成后：打开 PRD 编辑 / 导出 PRD / AI 修复（单条或严重+重要）
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import ThinkingStatus from '@/components/common/ThinkingStatus.vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Download,
  EditPen,
  MagicStick,
  Document,
  Clock,
  Checked,
  Loading,
  List,
  Connection,
  Check,
} from '@element-plus/icons-vue'
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
  { key: 'completeness', label: '功能完整性', desc: '遗漏场景、异常分支', icon: List, active: true },
  { key: 'consistency', label: '逻辑一致性', desc: '矛盾检查、术语统一', icon: Connection, active: true },
  { key: 'compliance', label: '合规性', desc: '安全要求、隐私标准', icon: Checked, active: true },
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
  { key: 'CRITICAL', label: '严重', color: '#dc2626', bg: '#fef2f2' },
  { key: 'MAJOR', label: '重要', color: '#d97706', bg: '#fffbeb' },
  { key: 'MINOR', label: '轻微', color: '#475569', bg: '#f1f5f9' },
  { key: 'SUGGESTION', label: '建议', color: '#ea580c', bg: '#fff7ed' },
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

let activeEventSource: EventSource | null = null
let activePollTimer: ReturnType<typeof setInterval> | null = null

onBeforeUnmount(() => {
  activeEventSource?.close()
  activeEventSource = null
  if (activePollTimer) {
    clearInterval(activePollTimer)
    activePollTimer = null
  }
})

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 审查服务...')
  activeEventSource?.close()
  const es = new EventSource(getTaskSseUrl(taskId))
  activeEventSource = es
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
  if (activePollTimer) clearInterval(activePollTimer)
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
      activePollTimer = null
    }
  }, 1500)
  activePollTimer = t
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
  activeEventSource?.close()
  const es = new EventSource(getTaskSseUrl(fixTaskId))
  activeEventSource = es
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
  if (s >= 80) return '#059669'
  if (s >= 60) return '#d97706'
  return '#dc2626'
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
      <!-- ====== 左侧配置面板 ====== -->
      <aside class="config-panel">
        <div class="panel-header">
          <div>
            <h2 class="module-title">PRD 审查</h2>
            <p class="module-subtitle">多维度完整性与逻辑审查</p>
          </div>
          <div class="panel-badge" title="PRD 质量与合规评估">
            <el-icon :size="15"><Checked /></el-icon>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">待审查 PRD 来源</label>
          <div class="mode-tabs">
            <button
              type="button"
              class="mode-tab"
              :class="{ active: inputMode === 'paste' }"
              @click="inputMode = 'paste'"
            >
              直接粘贴内容
            </button>
            <button
              type="button"
              class="mode-tab"
              :class="{ active: inputMode === 'existing' }"
              @click="inputMode = 'existing'"
            >
              选择文档库 PRD
            </button>
          </div>
        </div>

        <div v-if="inputMode === 'paste'" class="form-group">
          <el-input
            v-model="prdContent"
            type="textarea"
            :rows="9"
            placeholder="粘贴待审查的 PRD 文档正文（系统将自动关联分析与定位）..."
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
          <label class="form-label">审查评估维度</label>
          <div class="dimension-cards">
            <button
              v-for="dim in dimensions"
              :key="dim.key"
              type="button"
              class="dimension-card"
              :class="{ active: dim.active }"
              @click="toggleDim(dim)"
            >
              <div class="dim-icon-box">
                <el-icon :size="16"><component :is="dim.icon" /></el-icon>
              </div>
              <div class="dim-content">
                <span class="dim-label">{{ dim.label }}</span>
                <span class="dim-desc">{{ dim.desc }}</span>
              </div>
              <div class="dim-check">
                <el-icon v-if="dim.active" :size="12"><Check /></el-icon>
              </div>
            </button>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">定制侧重 (可选)</label>
          <el-input v-model="requirement" type="textarea" :rows="2" placeholder="例如：重点关注退款逆向流、支付接口超时与数据权限隔离" maxlength="500" />
        </div>

        <div class="generate-btn-wrap">
          <button
            type="button"
            class="btn-generate"
            :disabled="!canReview || fixing || loading"
            @click="handleReview"
          >
            <el-icon v-if="!loading" :size="16"><Checked /></el-icon>
            <span>{{ loading ? 'AI 正在深度审查中...' : '开始全面审查' }}</span>
          </button>
        </div>
      </aside>

      <!-- ====== 右侧报告区 ====== -->
      <main class="report-panel">
        <template v-if="showReport && !loading">
          <!-- 报告概览卡片 -->
          <div class="report-overview-card">
            <div class="report-top-row">
              <div class="score-badge-box" :style="{ borderColor: scoreColor(score) }">
                <span class="score-value" :style="{ color: scoreColor(score) }">{{ score }}</span>
                <span class="score-unit">综合评分</span>
              </div>

              <div class="report-summary-info">
                <div class="report-title-row">
                  <h3 class="report-title">质量审查诊断报告</h3>
                  <span v-if="linkedPrdId" class="linked-badge">已关联 PRD #{{ linkedPrdId }}</span>
                </div>
                <p class="summary-paragraph">{{ summary }}</p>

                <div class="severity-tags">
                  <span
                    v-for="s in severities"
                    :key="s.key"
                    v-show="issueCounts[s.key]"
                    class="sev-tag"
                    :style="{ background: s.bg, color: s.color, borderColor: s.color + '40' }"
                  >
                    {{ s.label }} · {{ issueCounts[s.key] }} 条
                  </span>
                </div>
              </div>
            </div>

            <div class="report-action-bar">
              <el-button type="primary" :disabled="!linkedPrdId" @click="openPrdEditor()">
                <el-icon><EditPen /></el-icon>
                打开 PRD 并对照编辑
              </el-button>
              <el-button
                :loading="fixingTarget === 'batch'"
                :disabled="!linkedPrdId || criticalMajorCount === 0 || (fixing && fixingTarget !== 'batch')"
                @click="aiFix()"
              >
                <el-icon><MagicStick /></el-icon>
                AI 自动修订严重/重要项 ({{ criticalMajorCount }})
              </el-button>
              <el-button :disabled="!linkedPrdId" @click="exportPrd">
                <el-icon><Document /></el-icon>
                导出完整 PRD
              </el-button>
              <el-button @click="exportReport">
                <el-icon><Download /></el-icon>
                导出审查报告
              </el-button>
            </div>
          </div>

          <!-- 问题列表 -->
          <div class="issues-container">
            <div class="issues-header">
              <h4 class="issues-heading">待优化问题项 ({{ issues.length }})</h4>
              <span class="issues-hint">点击每项右侧可直接跳转或定位修复</span>
            </div>

            <div class="issues-list">
              <article
                v-for="item in sortedIssues"
                :key="item.originalIndex"
                class="issue-card"
              >
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

                <div class="issue-suggestion-box">
                  <strong>建议方案：</strong>
                  <span>{{ item.issue.suggestion }}</span>
                </div>

                <div class="issue-footer-actions">
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :disabled="!linkedPrdId"
                    @click="openPrdEditor(item.originalIndex)"
                  >
                    去编辑此章节 →
                  </el-button>
                </div>
              </article>
            </div>

            <div v-if="issues.length === 0" class="empty-issues">
              🎉 本文档未发现结构性问题，规范性良好！
            </div>
          </div>
        </template>

        <!-- 空引导态 -->
        <template v-else-if="!loading && !fixing">
          <div class="onboarding-container">
            <div class="empty-icon-wrap">
              <el-icon :size="32" color="#0f172a"><Checked /></el-icon>
            </div>
            <h3 class="empty-title">AI 智能 PRD 质量把控</h3>
            <p class="empty-desc">
              粘贴 PRD 或选择已有文档，AI 将从完整性、前后置逻辑一致性、安全合规多维度进行审查，并支持一键智能修订。
            </p>

            <div class="dimension-guide-grid">
              <div class="d-guide-card">
                <span class="d-guide-tag">功能完整性</span>
                <p>排查异常分支、极端并发场景、逆向流程缺失</p>
              </div>
              <div class="d-guide-card">
                <span class="d-guide-tag">逻辑一致性</span>
                <p>术语统一、状态机闭环、前后章节数据口径校验</p>
              </div>
              <div class="d-guide-card">
                <span class="d-guide-tag">合规与隐私</span>
                <p>权限边界、敏感数据脱敏、审计追溯要求</p>
              </div>
            </div>
          </div>
        </template>

        <!-- 审查或修复中 -->
        <div v-if="loading || fixing" class="generating-container">
          <div class="status-box">
            <ThinkingStatus :steps="liveMessages" />
          </div>
          <div class="generating-meta">
            <span class="pulse-indicator" />
            <span>{{ progressMsg || (fixing ? 'AI 正在修订 PRD 章节并生成新版...' : 'AI 正在全面审查 PRD 逻辑，请稍候...') }}</span>
          </div>
        </div>
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
  color: #059669;
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

/* 审查维度卡片 */
.dimension-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dimension-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  background: #ffffff;
  cursor: pointer;
  text-align: left;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}
.dimension-card:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.dimension-card.active {
  border-color: #93c5fd;
  background: #eff6ff;
  box-shadow: inset 0 0 0 1px #bfdbfe;
}
.dim-icon-box {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #0f172a;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.dimension-card.active .dim-icon-box {
  background: #dbeafe;
  color: #2563eb;
}
.dim-content {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.dim-label {
  font-size: 12.5px;
  font-weight: 600;
  color: #0f172a;
}
.dim-desc {
  font-size: 11px;
  color: #94a3b8;
}
.dim-check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid #cbd5e1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-size: 10px;
}
.dimension-card.active .dim-check {
  background: #2563eb;
  border-color: #2563eb;
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

/* 报告主面板 */
.report-panel {
  flex: 1;
  background: #f8fafc;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.report-overview-card {
  margin: 24px 32px 16px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.03);
}
.report-top-row {
  display: flex;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 20px;
}
.score-badge-box {
  width: 72px;
  height: 72px;
  border-radius: 12px;
  border: 2.5px solid;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: #ffffff;
}
.score-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
}
.score-unit {
  font-size: 10px;
  color: #94a3b8;
  margin-top: 4px;
  font-weight: 500;
}
.report-summary-info {
  flex: 1;
  min-width: 0;
}
.report-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.report-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}
.linked-badge {
  font-size: 11px;
  font-weight: 500;
  color: #059669;
  background: #ecfdf5;
  border: 1px solid #dcfce7;
  padding: 1px 8px;
  border-radius: 9999px;
}
.summary-paragraph {
  margin: 0 0 12px;
  font-size: 13.5px;
  color: #475569;
  line-height: 1.6;
}
.severity-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.sev-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 6px;
  border: 1px solid;
}
.report-action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid #f1f5f9;
}

/* 问题项列表 */
.issues-container {
  padding: 0 32px 40px;
}
.issues-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}
.issues-heading {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.issues-hint {
  font-size: 12px;
  color: #94a3b8;
}
.issues-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.issue-card {
  background: #ffffff;
  border-radius: 10px;
  padding: 16px 20px;
  border: 1px solid #e2e8f0;
  transition: all 0.15s;
}
.issue-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.04);
}
.issue-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.sev-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}
.issue-dim {
  font-size: 12px;
  color: #64748b;
}
.issue-location {
  font-size: 12.5px;
  color: #ea580c;
  font-weight: 500;
  margin-left: auto;
  max-width: 50%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.issue-desc {
  font-size: 13.5px;
  color: #1e293b;
  margin: 0 0 10px;
  line-height: 1.6;
}
.issue-suggestion-box {
  font-size: 12.5px;
  color: #166534;
  background: #f0fdf4;
  border: 1px solid #dcfce7;
  padding: 8px 12px;
  border-radius: 6px;
  line-height: 1.5;
  margin-bottom: 8px;
}
.issue-footer-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}

.empty-issues {
  text-align: center;
  color: #059669;
  background: #ecfdf5;
  border: 1px dashed #a7f3d0;
  padding: 32px;
  border-radius: 10px;
  font-size: 14px;
}

/* 空引导态 */
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
.dimension-guide-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
}
.d-guide-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px;
  text-align: left;
}
.d-guide-tag {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
  display: block;
  margin-bottom: 4px;
}
.d-guide-card p {
  margin: 0;
  font-size: 11.5px;
  color: #94a3b8;
  line-height: 1.45;
}

/* 运行中 */
.generating-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: auto;
  width: 100%;
  max-width: 580px;
  padding: 40px 0;
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

@media (max-width: 1024px) {
  .page-container { padding: 16px; height: auto; min-height: 100%; }
  .workspace { flex-direction: column; height: auto; }
  .config-panel { width: 100%; border-right: none; border-bottom: 1px solid #e2e8f0; }
  .report-overview-card { margin: 16px 16px 12px; }
  .issues-container { padding: 0 16px 32px; }
  .dimension-guide-grid { grid-template-columns: 1fr; }
}
</style>
