<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getTaskSseUrl, getTaskById } from '@/api/task'
import client from '@/api/client'

// ==================== 输入 ====================
const inputMode = ref<'paste' | 'existing'>('paste')
const prdContent = ref('')
const prdDocumentId = ref<number | null>(null)
const existingPrds = ref<any[]>([])

// ==================== 审查维度（默认全选） ====================
const dimensions = ref([
  { key: 'completeness', label: '功能完整性', desc: '遗漏场景、异常分支', icon: 'List', active: true },
  { key: 'consistency', label: '逻辑一致性', desc: '矛盾检查、术语统一', icon: 'Connection', active: true },
  { key: 'compliance', label: '合规性', desc: '安全要求、隐私标准', icon: 'Checked', active: true },
])
const requirement = ref('')
function toggleDim(item: { key: string; active: boolean }) { item.active = !item.active }

// ==================== 状态 ====================
const loading = ref(false)
const progress = ref(0)
const progressMsg = ref('')
let taskId: number | null = null

// 审查结果
interface Issue { severity: string; dimension: string; location: string; description: string; suggestion: string }
const summary = ref('')
const score = ref(0)
const issues = ref<Issue[]>([])
const showReport = ref(false)
let reportId: number | null = null

const severities = [
  { key: 'CRITICAL', label: '严重', color: '#f56c6c', bg: '#fef0f0' },
  { key: 'MAJOR', label: '重要', color: '#e6a23c', bg: '#fdf6ec' },
  { key: 'MINOR', label: '轻微', color: '#909399', bg: '#f5f7fa' },
  { key: 'SUGGESTION', label: '建议', color: '#409eff', bg: '#ecf5ff' },
]

const severityOrder: Record<string, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, SUGGESTION: 3 }
const sortedIssues = computed(() => [...issues.value].sort((a, b) => (severityOrder[a.severity] ?? 9) - (severityOrder[b.severity] ?? 9)))
const issueCounts = computed(() => {
  const c: Record<string, number> = {}; issues.value.forEach(i => { c[i.severity] = (c[i.severity] || 0) + 1 }); return c
})

const canReview = computed(() => {
  const hasDims = dimensions.value.some(d => d.active)
  return (inputMode.value === 'paste' ? prdContent.value.trim().length > 0 : !!prdDocumentId.value) && hasDims
})

// ==================== 提交 ====================
async function handleReview() {
  if (inputMode.value === 'paste' && !prdContent.value.trim()) { ElMessage.warning('请输入 PRD 内容'); return }
  const dims = dimensions.value.filter(d => d.active).map(d => d.key)
  if (dims.length === 0) { ElMessage.warning('请至少选择一个审查维度'); return }

  loading.value = true; issues.value = []; showReport.value = false; summary.value = ''; score.value = 0
  progress.value = 0; progressMsg.value = ''; reportId = null

  try {
    const body: Record<string, any> = { dimensions: dims }
    if (inputMode.value === 'paste') body.prdContent = prdContent.value
    else body.prdDocumentId = prdDocumentId.value
    if (requirement.value) body.requirement = requirement.value

    const res = await client.post('/prd/review', body)
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
    if (d.progress >= 100) { es.close(); loading.value = false; ElMessage.success('审查完成'); fetchReport() }
  })
  es.onerror = () => { es.close(); loading.value = false }
}

async function fetchReport() {
  if (!taskId) return
  const t = setInterval(async () => {
    const r = await getTaskById(taskId!)
    if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
      clearInterval(t)
      reportId = r.data.data.resultRefId
      const report = await client.get(`/review/${reportId}`)
      const data = report.data.data
      try {
        let parsed: any = data.issues || '{}'
        for (let i = 0; i < 3 && typeof parsed === 'string'; i++) parsed = JSON.parse(parsed)
        summary.value = parsed.summary || ''
        score.value = parsed.score || 0
        issues.value = parsed.issues || []
      } catch { issues.value = [] }
      showReport.value = true
    }
    if (r.data.data.status === 'FAILED') { ElMessage.error(r.data.data.errorMessage || '审查失败'); clearInterval(t) }
  }, 2000)
}

/** 导出审查报告为 Word */
async function exportReport() {
  if (!reportId) return
  try {
    const res = await client.get(`/review/${reportId}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = `审查报告_${reportId}.docx`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

async function loadExistingPrds() {
  try {
    const response = await client.get('/documents', { params: { taskType: 'PRD_GENERATE', page: 0, size: 100 } })
    existingPrds.value = response.data.data?.content || []
  } catch { existingPrds.value = [] }
}

onMounted(loadExistingPrds)

function scoreColor(s: number) { if (s >= 80) return '#67c23a'; if (s >= 60) return '#e6a23c'; return '#f56c6c' }
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧面板 ====== -->
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">PRD 审查</h2>
          <el-icon size="18" color="#909399"><Clock /></el-icon>
        </div>

        <!-- PRD 来源 -->
        <div class="form-group">
          <label class="form-label">PRD 来源</label>
          <div class="mode-tabs">
            <div class="mode-tab" :class="{ active: inputMode === 'paste' }" @click="inputMode = 'paste'">直接粘贴</div>
            <div class="mode-tab" :class="{ active: inputMode === 'existing' }" @click="inputMode = 'existing'">选择已有PRD</div>
          </div>
        </div>
        <div v-if="inputMode === 'paste'" class="form-group">
          <el-input v-model="prdContent" type="textarea" :rows="10" placeholder="请粘贴需要审查的 PRD 文档内容..." maxlength="8000" show-word-limit />
        </div>
        <div v-else class="form-group">
          <el-select v-model="prdDocumentId" clearable filterable placeholder="选择要审查的文档" style="width:100%">
            <el-option v-for="doc in existingPrds" :key="doc.id" :label="doc.title" :value="doc.id" />
          </el-select>
        </div>

        <!-- 审查维度 -->
        <div class="form-group">
          <label class="form-label">审查维度</label>
          <div class="dimension-cards">
            <div v-for="dim in dimensions" :key="dim.key" class="dimension-card" :class="{ active: dim.active }" @click="toggleDim(dim)">
              <el-icon size="18"><component :is="dim.icon" /></el-icon>
              <span class="dim-label">{{ dim.label }}</span>
              <span class="dim-desc">{{ dim.desc }}</span>
            </div>
          </div>
        </div>

        <!-- 自定义要求 -->
        <div class="form-group">
          <label class="form-label">自定义要求（可选）</label>
          <el-input v-model="requirement" type="textarea" :rows="2" placeholder="例如：请重点关注支付流程的安全性" maxlength="500" />
        </div>

        <el-button class="btn-generate" :loading="loading" :disabled="!canReview" @click="handleReview">开始审查</el-button>
        <el-progress v-if="loading" :percentage="progress" :stroke-width="6" style="margin-top:12px" />
        <p v-if="progressMsg" style="color:#909399;font-size:13px;margin-top:6px">{{ progressMsg }}</p>
      </div>

      <!-- ====== 右侧报告区 ====== -->
      <div class="report-panel">
        <template v-if="showReport">
          <!-- 头部：评分 + 概述 -->
          <div class="report-header">
            <div class="score-circle" :style="{ borderColor: scoreColor(score) }">
              <span class="score-num" :style="{ color: scoreColor(score) }">{{ score }}</span>
              <span class="score-label">分</span>
            </div>
            <div class="report-summary">
              <div style="display:flex;justify-content:space-between;align-items:flex-start">
                <h3>审查报告</h3>
                <el-button type="primary" size="small" @click="exportReport">
                  <el-icon><Download /></el-icon> 导出 Word
                </el-button>
              </div>
              <p>{{ summary }}</p>
              <div class="severity-tags">
                <span v-for="s in severities" :key="s.key" v-show="issueCounts[s.key]" class="sev-tag" :style="{background:s.bg,color:s.color}">{{ s.label }} {{ issueCounts[s.key] }}</span>
              </div>
            </div>
          </div>
          <!-- 问题列表 -->
          <div class="issues-list">
            <div v-for="(issue, idx) in sortedIssues" :key="idx" class="issue-card">
              <div class="issue-head">
                <span class="sev-badge" :style="{background: (severities.find(s=>s.key===issue.severity)||{}).bg, color: (severities.find(s=>s.key===issue.severity)||{}).color}">
                  {{ (severities.find(s=>s.key===issue.severity)||{}).label || issue.severity }}
                </span>
                <span class="issue-dim">{{ issue.dimension }}</span>
                <span class="issue-location">{{ issue.location }}</span>
              </div>
              <p class="issue-desc">{{ issue.description }}</p>
              <p class="issue-suggestion"><strong>建议：</strong>{{ issue.suggestion }}</p>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="#dcdfe6"><Checked /></el-icon>
            <p class="placeholder-title">审查报告即将呈现</p>
            <p class="placeholder-desc">在左侧粘贴 PRD 文档并选择审查维度，AI 将从完整性、一致性、合规性三个维度进行全面评审</p>
          </div>
        </template>
        <div v-if="loading" class="preview-placeholder">
          <el-skeleton animated style="width:80%;max-width:500px">
            <template #template>
              <div style="display:flex;flex-direction:column;gap:16px;align-items:center">
                <el-skeleton-item variant="rect" style="width:100%;height:160px;border-radius:10px" />
                <el-skeleton-item variant="text" style="width:60%" />
                <el-skeleton-item variant="text" style="width:40%" />
                <el-skeleton-item variant="text" style="width:80%" />
              </div>
            </template>
          </el-skeleton>
          <p style="color:#909399;font-size:14px;margin-top:20px">{{ progressMsg || 'AI 正在审查 PRD，请稍候…' }}</p>
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

.mode-tabs { display: flex; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; }
.mode-tab { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #f5f7fa; color: #909399; transition: all .2s; user-select: none; }
.mode-tab.active { background: #409eff; color: #fff; font-weight: 500; }

/* 维度卡片 */
.dimension-cards { display: flex; flex-direction: column; gap: 8px; }
.dimension-card { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border: 1.5px solid #ebeef5; border-radius: 10px; cursor: pointer; background: #fafafa; transition: all .2s; }
.dimension-card.active { border-color: #409eff; background: #ecf5ff; }
.dimension-card .dim-label { font-size: 14px; font-weight: 500; color: #303133; flex-shrink: 0; }
.dimension-card .dim-desc { font-size: 12px; color: #909399; }

.btn-generate { width: 100%; height: 46px; font-size: 15px; border-radius: 10px; background: #c8c9cc !important; border-color: #c8c9cc !important; color: #fff !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #409eff !important; border-color: #409eff !important; }

:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }

/* ====== 右侧报告 ====== */
.report-panel { flex: 1; background: #f5f7fa; overflow-y: auto; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; min-height: 400px; }
.placeholder-title { font-size: 16px; color: #909399; margin: 20px 0 8px; }
.placeholder-desc { font-size: 13px; color: #c0c4cc; margin: 0; max-width: 320px; text-align: center; line-height: 1.7; }

.report-header { display: flex; gap: 24px; padding: 28px 32px; background: #fff; border-bottom: 1px solid #ebeef5; align-items: center; }
.score-circle { width: 80px; height: 80px; border-radius: 50%; border: 4px solid; display: flex; flex-direction: column; align-items: center; justify-content: center; flex-shrink: 0; }
.score-num { font-size: 28px; font-weight: 700; line-height: 1; }
.score-label { font-size: 12px; color: #909399; }
.report-summary { flex: 1; }
.report-summary h3 { margin: 0 0 8px; font-size: 18px; color: #303133; }
.report-summary p { margin: 0 0 10px; font-size: 14px; color: #606266; line-height: 1.6; }
.severity-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.sev-tag { font-size: 12px; padding: 2px 10px; border-radius: 20px; font-weight: 500; }

.issues-list { padding: 20px 32px; display: flex; flex-direction: column; gap: 14px; }
.issue-card { background: #fff; border-radius: 10px; padding: 18px 20px; border: 1px solid #ebeef5; }
.issue-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.sev-badge { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 4px; }
.issue-dim { font-size: 13px; color: #909399; }
.issue-location { font-size: 13px; color: #409eff; margin-left: auto; }
.issue-desc { font-size: 14px; color: #303133; margin: 0 0 8px; line-height: 1.6; }
.issue-suggestion { font-size: 13px; color: #67c23a; margin: 0; line-height: 1.5; background: #f0f9eb; padding: 8px 12px; border-radius: 6px; }
</style>
