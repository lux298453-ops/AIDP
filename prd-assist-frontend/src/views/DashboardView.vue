<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import {
  Document,
  Edit,
  PictureFilled,
  Checked,
  Folder,
  ArrowRight,
  Plus,
  Clock,
} from '@element-plus/icons-vue'
import { getDocumentList } from '@/api/document'
import type { DocumentVO } from '@/types/document'

const router = useRouter()
const user = useUserStore()

const loading = ref(false)
const allDocs = ref<DocumentVO[]>([])

/** 拉取最新项目文档 */
async function fetchDashboardDocs() {
  loading.value = true
  try {
    const res = await getDocumentList({ page: 0, size: 50 })
    allDocs.value = res.data?.data?.content || []
  } catch (e) {
    console.error('获取工作台文档列表失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(fetchDashboardDocs)

/** 资产统计 */
const stats = computed(() => {
  let prd = 0, prototype = 0, review = 0
  for (const d of allDocs.value) {
    const t = String(d.docType || '').toUpperCase()
    if (t === 'PRD') prd++
    else if (t === 'PROTOTYPE') prototype++
    else if (t === 'REVIEW') review++
  }
  return {
    total: allDocs.value.length,
    prd,
    prototype,
    review,
  }
})

/** 最近活跃文档（取前 6 条） */
const recentDocs = computed(() => allDocs.value.slice(0, 6))

function goDetail(doc: DocumentVO) {
  const type = String(doc?.docType || '').toUpperCase()
  const id = Number(doc?.id)
  if (!id) return
  if (type === 'PRD') router.push(`/prd/${id}`)
  else if (type === 'PROTOTYPE') router.push(`/prototype/${id}`)
  else if (type === 'REVIEW') router.push({ path: '/prd/review', query: { reportId: String(id) } })
}

function formatDocDate(s?: string) {
  if (!s) return ''
  const clean = s.replace('T', ' ')
  return clean.length >= 16 ? clean.substring(0, 16) : clean
}

const docTypeConfig: Record<string, { label: string; bg: string; text: string; border: string; dot: string }> = {
  PRD: { label: 'PRD', bg: 'bg-blue-50/90', text: 'text-blue-700', border: 'border-blue-200/80', dot: 'bg-blue-500' },
  PROTOTYPE: { label: '原型', bg: 'bg-orange-50/80', text: 'text-orange-700', border: 'border-orange-200/70', dot: 'bg-orange-500' },
  REVIEW: { label: '审查', bg: 'bg-emerald-50/80', text: 'text-emerald-700', border: 'border-emerald-200/70', dot: 'bg-emerald-500' },
}

function getDocTypeMeta(typeStr?: string) {
  const t = String(typeStr || '').toUpperCase()
  return docTypeConfig[t] || {
    label: t || '未知',
    bg: 'bg-slate-100',
    text: 'text-slate-600',
    border: 'border-slate-200',
    dot: 'bg-slate-400',
  }
}

/** 核心功能通道 */
const workflows = [
  {
    title: '新建 PRD 文档',
    desc: '结构化功能拆解与用例撰写',
    path: '/prd/generate',
    icon: Document,
    colorClass: 'wf-blue',
  },
  {
    title: '图表与流程推导',
    desc: '自动推导页面流图与 Mermaid 架构',
    path: '/prd/enhance',
    icon: Edit,
    colorClass: 'wf-indigo',
  },
  {
    title: '交互原型设计',
    desc: '生成 Web / 移动端可点选高保真原型',
    path: '/prototype',
    icon: PictureFilled,
    colorClass: 'wf-orange',
  },
  {
    title: 'PRD 质量审查',
    desc: '多维审查排查遗漏场景与冲突缺陷',
    path: '/prd/review',
    icon: Checked,
    colorClass: 'wf-emerald',
  },
]
</script>

<template>
  <div class="workspace-page">
    <div class="workspace-container">
      <!-- ====== 顶部工作台问候与快捷动作 ====== -->
      <section class="workspace-hero">
        <div class="hero-main">
          <h1 class="hero-title">你好，{{ user.nickname || user.username }}</h1>
          <p class="hero-desc">
            需求规格分析、架构图表推导与高保真交互原型统一协作工作台
          </p>
        </div>

        <div class="hero-actions">
          <button class="hero-action-btn hero-action-btn--primary" @click="router.push('/prd/generate')">
            <el-icon :size="14"><Plus /></el-icon>
            <span>新建 PRD</span>
          </button>
          <button class="hero-action-btn hero-action-btn--secondary" @click="router.push('/prototype')">
            <el-icon :size="14"><PictureFilled /></el-icon>
            <span>推导原型</span>
          </button>
          <button class="hero-action-btn hero-action-btn--secondary" @click="router.push('/prd/review')">
            <el-icon :size="14"><Checked /></el-icon>
            <span>审查报告</span>
          </button>
        </div>
      </section>

      <!-- ====== 核心指标概览卡片 ====== -->
      <section class="metrics-grid">
        <div class="metric-card" @click="router.push('/documents')">
          <div class="metric-header">
            <div class="metric-icon-wrap icon-blue">
              <el-icon :size="18"><Document /></el-icon>
            </div>
            <span class="metric-trend-hint">规范资产</span>
          </div>
          <div class="metric-body">
            <span class="metric-val">{{ stats.prd }}</span>
            <span class="metric-name">PRD 需求文档</span>
          </div>
          <div class="metric-foot">
            <span>功能拆解与业务章节</span>
            <el-icon :size="12" class="arrow"><ArrowRight /></el-icon>
          </div>
        </div>

        <div class="metric-card" @click="router.push('/documents')">
          <div class="metric-header">
            <div class="metric-icon-wrap icon-orange">
              <el-icon :size="18"><PictureFilled /></el-icon>
            </div>
            <span class="metric-trend-hint">前端交互</span>
          </div>
          <div class="metric-body">
            <span class="metric-val">{{ stats.prototype }}</span>
            <span class="metric-name">交互原型项目</span>
          </div>
          <div class="metric-foot">
            <span>Web / 移动端高保真草稿</span>
            <el-icon :size="12" class="arrow"><ArrowRight /></el-icon>
          </div>
        </div>

        <div class="metric-card" @click="router.push('/documents')">
          <div class="metric-header">
            <div class="metric-icon-wrap icon-emerald">
              <el-icon :size="18"><Checked /></el-icon>
            </div>
            <span class="metric-trend-hint">合规风控</span>
          </div>
          <div class="metric-body">
            <span class="metric-val">{{ stats.review }}</span>
            <span class="metric-name">质量审查报告</span>
          </div>
          <div class="metric-foot">
            <span>遗漏场景与逻辑分析</span>
            <el-icon :size="12" class="arrow"><ArrowRight /></el-icon>
          </div>
        </div>

        <div class="metric-card" @click="router.push('/documents')">
          <div class="metric-header">
            <div class="metric-icon-wrap icon-slate">
              <el-icon :size="18"><Folder /></el-icon>
            </div>
            <span class="metric-trend-hint">历史沉淀</span>
          </div>
          <div class="metric-body">
            <span class="metric-val">{{ stats.total }}</span>
            <span class="metric-name">累计项目资产</span>
          </div>
          <div class="metric-foot">
            <span>全生命周期归档管理</span>
            <el-icon :size="12" class="arrow"><ArrowRight /></el-icon>
          </div>
        </div>
      </section>

      <!-- ====== 主体双栏：最近活跃工作区 (68%) + 快捷功能通道 (32%) ====== -->
      <section class="main-split-layout">
        <!-- 左侧：最近编辑与活跃记录 -->
        <div class="split-left">
          <div class="panel-card">
            <div class="panel-header">
              <div class="panel-title-group">
                <h2 class="panel-title">最近工作</h2>
                <span class="panel-subtitle">按更新时间排列的项目资产与草稿</span>
              </div>
              <button class="panel-link-btn" @click="router.push('/documents')">
                <span>全部文档 ({{ stats.total }})</span>
                <el-icon :size="12"><ArrowRight /></el-icon>
              </button>
            </div>

            <div v-if="loading" class="panel-loading">
              <span class="text-xs text-slate-400">正在同步最近文档资产...</span>
            </div>

            <div v-else-if="recentDocs.length === 0" class="panel-empty">
              <div class="empty-icon-wrap">
                <el-icon :size="28" class="text-slate-300"><Document /></el-icon>
              </div>
              <h4 class="empty-title">暂无文档记录</h4>
              <p class="empty-desc">开始撰写您的第一份 PRD，或导入已有 XMind / Word 需求素材</p>
              <button class="action-pill-btn action-pill-btn--primary" @click="router.push('/prd/generate')">
                <el-icon :size="12"><Plus /></el-icon>
                <span>立即新建 PRD</span>
              </button>
            </div>

            <div v-else class="recent-doc-list">
              <div
                v-for="doc in recentDocs"
                :key="doc.docType + doc.id"
                class="recent-doc-row group"
                @click="goDetail(doc)"
              >
                <!-- 左侧类型徽章 + 标题与简要 -->
                <div class="doc-row-main">
                  <div class="doc-row-top">
                    <span
                      class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border text-[11px] font-medium leading-none flex-shrink-0"
                      :class="[getDocTypeMeta(doc.docType).bg, getDocTypeMeta(doc.docType).text, getDocTypeMeta(doc.docType).border]"
                    >
                      <span class="w-1.5 h-1.5 rounded-full" :class="getDocTypeMeta(doc.docType).dot" />
                      <span>{{ getDocTypeMeta(doc.docType).label }}</span>
                    </span>
                    <h3 class="doc-row-title group-hover:text-blue-600 transition-colors">
                      {{ doc.title }}
                    </h3>
                  </div>
                  <p class="doc-row-desc">{{ doc.description || '暂无详细描述' }}</p>
                </div>

                <!-- 右侧时间与进入按钮 -->
                <div class="doc-row-aside" @click.stop>
                  <span class="doc-row-time flex items-center gap-1">
                    <el-icon :size="11"><Clock /></el-icon>
                    <span>{{ formatDocDate(doc.createdAt) }}</span>
                  </span>
                  <button
                    type="button"
                    class="action-pill-btn action-pill-btn--primary"
                    @click="goDetail(doc)"
                  >
                    <span>打开</span>
                    <el-icon :size="11"><ArrowRight /></el-icon>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧：核心功能通道与指引 -->
        <div class="split-right">
          <div class="panel-card">
            <div class="panel-header">
              <div class="panel-title-group">
                <h2 class="panel-title">快捷通道</h2>
                <span class="panel-subtitle">常用业务工作流</span>
              </div>
            </div>

            <div class="workflow-stack">
              <div
                v-for="wf in workflows"
                :key="wf.path"
                class="workflow-item group"
                @click="router.push(wf.path)"
              >
                <div class="wf-icon-box" :class="wf.colorClass">
                  <el-icon :size="16"><component :is="wf.icon" /></el-icon>
                </div>
                <div class="wf-info">
                  <div class="wf-name group-hover:text-blue-600 transition-colors">{{ wf.title }}</div>
                  <div class="wf-caption">{{ wf.desc }}</div>
                </div>
                <el-icon :size="13" class="wf-chevron"><ArrowRight /></el-icon>
              </div>
            </div>

            <!-- 底部文档库引导条 -->
            <div class="archive-banner" @click="router.push('/documents')">
              <div class="flex items-center gap-2">
                <el-icon :size="15" class="text-blue-600"><Folder /></el-icon>
                <span class="text-xs font-semibold text-slate-700">进入文档中心</span>
              </div>
              <span class="text-[11px] text-slate-400">时间分期与归档管理 →</span>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workspace-page {
  padding: 24px 28px 48px;
  background-color: #f8fafc;
  min-height: 100%;
}
.workspace-container {
  max-width: 1240px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ====== 顶部工作台问候 ====== */
.workspace-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 22px;
  background: #ffffff;
  border-radius: 10px;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.07), 0 1px 3px rgba(15, 23, 42, 0.03);
}
.hero-main {
  flex: 1;
  min-width: 0;
}
.hero-title {
  margin: 0 0 4px;
  font-size: 19px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: -0.025em;
}
.hero-desc {
  margin: 0;
  font-size: 13px;
  color: #64748b;
  line-height: 1.5;
}
.hero-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.hero-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 13px;
  border-radius: 6px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  white-space: nowrap;
  user-select: none;
}
.hero-action-btn--primary {
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%);
  color: #ffffff;
  border: 1px solid #2563eb;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.22), 0 1px 2px rgba(37, 99, 235, 0.25);
}
.hero-action-btn--primary:hover {
  background: linear-gradient(180deg, #2563eb 0%, #1d4ed8 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.18), 0 2px 4px rgba(37, 99, 235, 0.3);
}
.hero-action-btn--primary:active {
  transform: translateY(1px);
}
.hero-action-btn--secondary {
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  color: #334155;
  border: 1px solid #e2e8f0;
  box-shadow: inset 0 1px 0 #ffffff, 0 1px 2px rgba(15, 23, 42, 0.04);
}
.hero-action-btn--secondary:hover {
  background: #f1f5f9;
  color: #0f172a;
  border-color: #cbd5e1;
}
.hero-action-btn--secondary:active {
  transform: translateY(1px);
}

/* ====== 核心指标网格 ====== */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.metric-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 15px 16px;
  background: #ffffff;
  border-radius: 9px;
  cursor: pointer;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.07), 0 1px 2px rgba(15, 23, 42, 0.03);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.metric-card:hover {
  box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.4), 0 4px 12px -2px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
}
.metric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.metric-icon-wrap {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.icon-blue { background: #eff6ff; color: #2563eb; }
.icon-orange { background: #fff7ed; color: #ea580c; }
.icon-emerald { background: #ecfdf5; color: #059669; }
.icon-slate { background: #f1f5f9; color: #475569; }

.metric-trend-hint {
  font-size: 11px;
  font-weight: 500;
  color: #94a3b8;
}
.metric-body {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.metric-val {
  font-size: 24px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1;
  font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}
.metric-name {
  font-size: 12.5px;
  font-weight: 500;
  color: #64748b;
}
.metric-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px solid #f1f5f9;
  font-size: 11.5px;
  color: #94a3b8;
}
.metric-card:hover .arrow {
  color: #2563eb;
  transform: translateX(2px);
}
.arrow {
  transition: all 0.15s ease;
}

/* ====== 主体双栏 ====== */
.main-split-layout {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}
@media (min-width: 960px) {
  .main-split-layout {
    grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  }
}
.panel-card {
  background: #ffffff;
  border-radius: 10px;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.07), 0 1px 3px rgba(15, 23, 42, 0.03);
  overflow: hidden;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #f1f5f9;
}
.panel-title-group {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.panel-subtitle {
  font-size: 12px;
  color: #94a3b8;
}
.panel-link-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  font-weight: 500;
  color: #2563eb;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  transition: all 0.15s ease;
}
.panel-link-btn:hover {
  background: #eff6ff;
}

/* 最近文档列表 */
.recent-doc-list {
  display: flex;
  flex-direction: column;
  divide-y: 1px solid #f1f5f9;
}
.recent-doc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 20px;
  cursor: pointer;
  transition: background-color 0.12s ease;
}
.recent-doc-row:hover {
  background-color: #f8fafc;
}
.doc-row-main {
  min-width: 0;
  flex: 1;
}
.doc-row-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 3px;
}
.doc-row-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 500;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-row-desc {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-row-aside {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}
.doc-row-time {
  font-size: 11.5px;
  font-family: ui-monospace, monospace;
  color: #94a3b8;
  white-space: nowrap;
}

/* 空状态与加载中 */
.panel-loading {
  padding: 48px;
  text-align: center;
}
.panel-empty {
  padding: 48px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.empty-icon-wrap {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}
.empty-title {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}
.empty-desc {
  margin: 0 0 16px;
  font-size: 12px;
  color: #94a3b8;
  max-width: 320px;
}

/* 右侧工作流卡片 */
.workflow-stack {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}
.workflow-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}
.workflow-item:hover {
  background: #f8fafc;
  border-color: #e2e8f0;
  transform: translateX(2px);
}
.wf-icon-box {
  width: 32px;
  height: 32px;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.wf-blue { background: #eff6ff; color: #2563eb; }
.wf-indigo { background: #eef2ff; color: #4f46e5; }
.wf-orange { background: #fff7ed; color: #ea580c; }
.wf-emerald { background: #ecfdf5; color: #059669; }

.wf-info {
  flex: 1;
  min-width: 0;
}
.wf-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 2px;
}
.wf-caption {
  font-size: 11.5px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-chevron {
  color: #cbd5e1;
  transition: all 0.15s ease;
}
.workflow-item:hover .wf-chevron {
  color: #2563eb;
  transform: translateX(2px);
}

.archive-banner {
  margin: 4px 12px 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  transition: all 0.15s ease;
}
.archive-banner:hover {
  background: #eff6ff;
  border-color: #93c5fd;
}

/* 微胶囊按键（与全局统一标准） */
.action-pill-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  height: 24px;
  border-radius: 5px;
  font-size: 11.5px;
  font-weight: 500;
  color: #475569;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.action-pill-btn--primary {
  color: #2563eb;
  background: #eff6ff;
  border-color: #dbeafe;
}
.action-pill-btn--primary:hover {
  background: #2563eb;
  color: #ffffff;
  border-color: #2563eb;
  box-shadow: 0 1px 3px rgba(37, 99, 235, 0.25);
}

/* 响应式 */
@media (max-width: 960px) {
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .workspace-hero {
    flex-direction: column;
    align-items: flex-start;
  }
  .hero-actions {
    width: 100%;
  }
  .hero-action-btn {
    flex: 1;
    justify-content: center;
  }
}

@media (max-width: 600px) {
  .workspace-page {
    padding: 16px 14px 40px;
  }
  .metrics-grid {
    grid-template-columns: 1fr;
  }
  .doc-row-aside {
    flex-direction: column;
    align-items: flex-end;
    gap: 6px;
  }
}
</style>
