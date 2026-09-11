<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, List, Grid, Download, Refresh, Delete, Folder, FolderOpened, ArrowRight, Calendar, Clock, Expand, Fold } from '@element-plus/icons-vue'
import { getDocumentList } from '@/api/document'
import client from '@/api/client'
import type { DocumentVO } from '@/types/document'

const router = useRouter()

// ==================== 视图展示模式 ====================
// 'tree' 为树形抽屉分层，'card' 为分期卡片墙
const displayLayout = ref<'tree' | 'card'>('tree')

// ==================== 时间粒度模式 ====================
// 'day' 按天细分（推荐，最近14天逐日），'month' 按月聚合
const timeGranularity = ref<'day' | 'month'>('day')

// ==================== 筛选 + 搜索 ====================
const keyword = ref('')
const selectedType = ref<string>('ALL') // 'ALL' | 'PRD' | 'PROTOTYPE' | 'REVIEW'

// 时间快捷切片: 'ALL' | 'TODAY' | '3DAYS' | '7DAYS' | 'CUSTOM'
const selectedTimePreset = ref<'ALL' | 'TODAY' | '3DAYS' | '7DAYS' | 'CUSTOM'>('ALL')
const customDateRange = ref<[Date, Date] | null>(null)

const taskTypeOptions = [
  { label: '全部业务', value: 'ALL', count: 0 },
  { label: 'PRD 文档', value: 'PRD', count: 0 },
  { label: '交互原型', value: 'PROTOTYPE', count: 0 },
  { label: '审查报告', value: 'REVIEW', count: 0 },
]

// ==================== 智能时间分期归档 ====================
const docs = ref<DocumentVO[]>([])
const loading = ref(false)
const expandedTimeNodes = ref<Record<string, boolean>>({})

// 时间节点类型：今天、昨天、本周内、上周、本月更早、更早归档
interface TimeSection {
  id: string
  title: string
  subtitle: string
  isRecent: boolean
  items: DocumentVO[]
  typeCounts: { PRD: number; PROTOTYPE: number; REVIEW: number }
}

function parseDocDate(dateStr?: string): Date {
  if (!dateStr) return new Date(0)
  return new Date(dateStr.replace(' ', 'T'))
}

// 经过类型、时间切片和关键字过滤后的列表
const filteredDocs = computed(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const oneDayMs = 24 * 3600 * 1000

  const list = docs.value.filter(doc => {
    // 1. 类型过滤
    if (selectedType.value !== 'ALL') {
      const type = String(doc.docType || '').toUpperCase()
      if (type !== selectedType.value) return false
    }

    // 2. 关键词过滤
    if (keyword.value.trim()) {
      const q = keyword.value.trim().toLowerCase()
      const matchTitle = (doc.title || '').toLowerCase().includes(q)
      const matchDesc = (doc.description || '').toLowerCase().includes(q)
      if (!matchTitle && !matchDesc) return false
    }

    // 3. 时间快捷切片与日历过滤
    if (selectedTimePreset.value !== 'ALL') {
      const docTime = parseDocDate(doc.createdAt).getTime()
      if (selectedTimePreset.value === 'TODAY') {
        if (docTime < todayStart) return false
      } else if (selectedTimePreset.value === '3DAYS') {
        if (docTime < todayStart - 2 * oneDayMs) return false
      } else if (selectedTimePreset.value === '7DAYS') {
        if (docTime < todayStart - 6 * oneDayMs) return false
      } else if (selectedTimePreset.value === 'CUSTOM' && customDateRange.value && customDateRange.value.length === 2) {
        const [start, end] = customDateRange.value
        const startTime = new Date(start.getFullYear(), start.getMonth(), start.getDate()).getTime()
        const endTime = new Date(end.getFullYear(), end.getMonth(), end.getDate(), 23, 59, 59, 999).getTime()
        if (docTime < startTime || docTime > endTime) return false
      }
    }

    return true
  })

  // 确保按创建时间严格倒序排列（最新在上）
  return list.sort((a, b) => {
    const tA = parseDocDate(a.createdAt).getTime()
    const tB = parseDocDate(b.createdAt).getTime()
    return tB - tA
  })
})

// 各业务类型的统计（跟随当前时间切片与搜索词联动）
const typeStatistics = computed(() => {
  const stats = { ALL: docs.value.length, PRD: 0, PROTOTYPE: 0, REVIEW: 0 }
  for (const d of docs.value) {
    const t = String(d.docType || '').toUpperCase()
    if (t === 'PRD') stats.PRD++
    else if (t === 'PROTOTYPE') stats.PROTOTYPE++
    else if (t === 'REVIEW') stats.REVIEW++
  }
  return stats
})

// 星期几中文映射
const weekDayMap = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

// 智能时间层级归档：近 14 天逐日精确划分，更早按月归档
const timeSections = computed<TimeSection[]>(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const oneDayMs = 24 * 3600 * 1000

  // 收集分组，使用有序 Map 保持时间从新到旧
  const sectionMap = new Map<string, { title: string; subtitle: string; isRecent: boolean; items: DocumentVO[] }>()

  const ensureGroup = (id: string, title: string, subtitle: string, isRecent = false) => {
    if (!sectionMap.has(id)) {
      sectionMap.set(id, { title, subtitle, isRecent, items: [] })
    }
    return sectionMap.get(id)!
  }

  for (const doc of filteredDocs.value) {
    const d = parseDocDate(doc.createdAt)
    const dateStr = (doc.createdAt || '').substring(0, 10)
    const docDayStart = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
    const diffDays = Math.round((todayStart - docDayStart) / oneDayMs)

    if (timeGranularity.value === 'month') {
      // 宏观按月归档
      const monthStr = dateStr ? dateStr.substring(0, 7) : '更早'
      const isThisMonth = monthStr === toDateStr(now).substring(0, 7)
      const title = isThisMonth ? '本月归档' : `${monthStr} 归档`
      ensureGroup(`month-${monthStr}`, title, `${monthStr} 自然月度记录`, isThisMonth).items.push(doc)
    } else {
      // 默认：微观精细「按天划分」（近 14 天每一天都有独立归档，杜绝跨天挤在同一个组）
      if (diffDays <= 0) {
        const weekDay = weekDayMap[d.getDay()] || '今天'
        ensureGroup('day-today', '今天', `${dateStr} · ${weekDay} · 最新动态`, true).items.push(doc)
      } else if (diffDays === 1) {
        const weekDay = weekDayMap[d.getDay()] || '昨天'
        ensureGroup('day-yesterday', '昨天', `${dateStr} · ${weekDay}`, true).items.push(doc)
      } else if (diffDays === 2) {
        const weekDay = weekDayMap[d.getDay()] || '前天'
        ensureGroup('day-before-yesterday', '前天', `${dateStr} · ${weekDay}`, true).items.push(doc)
      } else if (diffDays > 2 && diffDays < 14) {
        const weekDay = weekDayMap[d.getDay()] || ''
        ensureGroup(`day-${dateStr}`, `${dateStr} (${weekDay})`, `${diffDays} 天前`, true).items.push(doc)
      } else {
        // 超过 14 天的更早历史记录，自动按自然月份聚合收拢，保持界面清爽整洁
        const monthStr = dateStr ? dateStr.substring(0, 7) : '更早'
        ensureGroup(`month-${monthStr}`, `${monthStr} 历史归档`, '较早时间记录', false).items.push(doc)
      }
    }
  }

  // 转换为数组并计算每个时间段内各业务类型的数量
  const list: TimeSection[] = []
  for (const [id, grp] of sectionMap.entries()) {
    if (grp.items.length === 0) continue
    const counts = { PRD: 0, PROTOTYPE: 0, REVIEW: 0 }
    for (const item of grp.items) {
      const t = String(item.docType || '').toUpperCase()
      if (t === 'PRD') counts.PRD++
      else if (t === 'PROTOTYPE') counts.PROTOTYPE++
      else if (t === 'REVIEW') counts.REVIEW++
    }
    list.push({
      id,
      title: grp.title,
      subtitle: grp.subtitle,
      isRecent: grp.isRecent,
      items: grp.items,
      typeCounts: counts,
    })
  }

  return list
})

function toDateStr(d: Date) {
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function isNodeOpen(sectionId: string) {
  // 默认全部展开或用户设定的状态
  if (expandedTimeNodes.value[sectionId] !== undefined) {
    return expandedTimeNodes.value[sectionId]
  }
  // 默认展开所有时间节点以提供直观全局感知
  return true
}

function toggleNode(sectionId: string) {
  expandedTimeNodes.value[sectionId] = !isNodeOpen(sectionId)
}

function expandAll() {
  timeSections.value.forEach(s => { expandedTimeNodes.value[s.id] = true })
}

function collapseAll() {
  timeSections.value.forEach(s => { expandedTimeNodes.value[s.id] = false })
}

function formatTime(s: string) {
  return s && s.length >= 16 ? s.substring(11, 16) : ''
}

function formatShortDate(s?: string) {
  if (!s || s.length < 16) return ''
  const clean = s.replace('T', ' ')
  return `${clean.substring(5, 10)} ${clean.substring(11, 16)}`
}

// ==================== 加载 ====================
async function loadDocs() {
  loading.value = true
  try {
    const res = await getDocumentList({
      page: 0,
      size: 500, // 树形工作台拉取充足记录以进行分层归类
    })
    docs.value = res.data.data.content || []
  } catch { /* handled */ }
  finally { loading.value = false }
}

onMounted(loadDocs)

// ==================== 导航：跳到对应模块的预览/详情页 ====================
function goDetail(doc: DocumentVO) {
  const type = String(doc?.docType || '').toUpperCase()
  const id = Number(doc?.id)
  if (!id) {
    ElMessage.warning('文档 ID 无效')
    return
  }

  if (type === 'PRD') {
    router.push(`/prd/${id}`)
    return
  }
  if (type === 'PROTOTYPE') {
    router.push(`/prototype/${id}`)
    return
  }
  if (type === 'REVIEW') {
    router.push({ path: '/prd/review', query: { reportId: String(id) } })
    return
  }
  ElMessage.warning(`未知文档类型：${doc?.docType || '空'}`)
}

// ==================== 业务类型徽章配置（轻量、精致、浅底微边框） ====================
const docTypeConfig: Record<string, {
  label: string
  bg: string
  text: string
  border: string
  dot: string
}> = {
  PRD: {
    label: 'PRD',
    bg: 'bg-blue-50/90',
    text: 'text-blue-700',
    border: 'border-blue-200/80',
    dot: 'bg-blue-500',
  },
  PROTOTYPE: {
    label: '原型',
    bg: 'bg-orange-50/80',
    text: 'text-orange-700',
    border: 'border-orange-200/70',
    dot: 'bg-orange-500',
  },
  REVIEW: {
    label: '审查',
    bg: 'bg-emerald-50/80',
    text: 'text-emerald-700',
    border: 'border-emerald-200/70',
    dot: 'bg-emerald-500',
  },
}

function getDocTypeMeta(typeStr: string) {
  const t = String(typeStr || '').toUpperCase()
  return docTypeConfig[t] || {
    label: t || '未知',
    bg: 'bg-slate-100',
    text: 'text-slate-600',
    border: 'border-slate-200',
    dot: 'bg-slate-400',
  }
}

function formatDate(s: string) { return s ? s.substring(0, 10) + ' ' + s.substring(11, 16) : '' }

/** 导出 Word */
async function exportWord(doc: DocumentVO) {
  try {
    const res = await client.get(`/prd/${doc.id}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = `${doc.title}.docx`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('下载成功')
  } catch (e: any) {
    console.error('导出失败:', e)
    ElMessage.error(e?.response?.data?.message || e?.message || '下载失败')
  }
}

/** 导出原型 HTML */
async function exportPrototype(doc: DocumentVO) {
  try {
    const res = await client.get(`/prototype/${doc.id}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data], { type: 'text/html' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${doc.title}.html`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

/** 导出审查报告 Word */
async function exportReview(doc: DocumentVO) {
  try {
    const res = await client.get(`/review/${doc.id}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = `${doc.title}.docx`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

/** 删除文档 */
async function handleDelete(doc: DocumentVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${doc.title}」吗？删除后不可恢复。`,
      '删除文档',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await client.delete(`/documents/${doc.docType}/${doc.id}`)
    ElMessage.success('删除成功')
    loadDocs()
  } catch (e: any) {
    console.error('删除失败:', e)
  }
}

/** 重新生成 */
async function handleRegenerate(doc: DocumentVO) {
  if (!doc.taskId) return
  try {
    await ElMessageBox.confirm(
      `将基于原有参数重新生成「${doc.title}」，旧结果会保留。确认继续？`,
      '重新生成',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' }
    )
    const res = await client.post(`/task/${doc.taskId}/regenerate`)
    const newTaskId = res.data?.data?.taskId
    ElMessage.success(`重新生成已提交 (taskId=${newTaskId})，请稍候查看`)
    setTimeout(loadDocs, 3000)
  } catch {
    // 用户取消或失败
  }
}
</script>

<template>
  <div class="doc-list-page max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <!-- 顶栏标题区 -->
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
      <div>
        <h3 class="text-xl sm:text-2xl font-bold text-slate-900 tracking-tight m-0">我的文档库</h3>
        <p class="text-xs sm:text-sm text-slate-500 mt-1 mb-0">基于时间生命周期的分层归档，实时掌握 PRD、原型与审查进度</p>
      </div>

      <!-- 搜索与展示模式切换（统一 36px 现代高度与精致对齐） -->
      <div class="flex items-center gap-3">
        <el-input
          v-model="keyword"
          placeholder="在当前归档中检索..."
          clearable
          class="custom-search-input"
          style="width: 240px"
        >
          <template #prefix><el-icon class="text-slate-400"><Search /></el-icon></template>
        </el-input>

        <!-- 现代分段选择器（与搜索框严格对齐 36px 高度） -->
        <div class="view-mode-switcher">
          <button
            type="button"
            class="switcher-btn"
            :class="{ active: displayLayout === 'tree' }"
            @click="displayLayout = 'tree'"
            title="树形抽屉分层视图"
          >
            <el-icon :size="14"><Folder /></el-icon>
            <span>树形分层</span>
          </button>
          <button
            type="button"
            class="switcher-btn"
            :class="{ active: displayLayout === 'card' }"
            @click="displayLayout = 'card'"
            title="分期卡片墙视图"
          >
            <el-icon :size="14"><Grid /></el-icon>
            <span>分期卡片</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 双轨筛选栏：业务分类 + 时间切片与粒度控制 -->
    <div class="flex flex-col gap-3 mb-6 pb-4 border-b border-slate-200/80">
      <!-- 上行：业务分类筛选 -->
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex flex-wrap items-center gap-1.5">
          <span class="text-xs font-medium text-slate-400 mr-1">业务分类:</span>
          <button
            type="button"
            class="px-2.5 py-1 text-xs font-medium rounded-md transition-all flex items-center gap-1.5"
            :class="selectedType === 'ALL'
              ? 'bg-blue-600 text-white shadow-2xs'
              : 'bg-white text-slate-600 border border-slate-200 hover:border-slate-300 hover:bg-slate-50'"
            @click="selectedType = 'ALL'"
          >
            <span>全部</span>
            <span class="px-1.5 py-0.2 rounded-full text-[10px]" :class="selectedType === 'ALL' ? 'bg-blue-700 text-white' : 'bg-slate-100 text-slate-500'">
              {{ typeStatistics.ALL }}
            </span>
          </button>

          <button
            type="button"
            class="px-2.5 py-1 text-xs font-medium rounded-md transition-all flex items-center gap-1.5"
            :class="selectedType === 'PRD'
              ? 'bg-blue-600 text-white shadow-2xs'
              : 'bg-white text-slate-600 border border-slate-200 hover:border-slate-300 hover:bg-slate-50'"
            @click="selectedType = 'PRD'"
          >
            <span class="w-1.5 h-1.5 rounded-full inline-block" :class="selectedType === 'PRD' ? 'bg-white' : 'bg-blue-500'" />
            <span>PRD 需求文档</span>
            <span class="px-1.5 py-0.2 rounded-full text-[10px]" :class="selectedType === 'PRD' ? 'bg-blue-700 text-white' : 'bg-slate-100 text-slate-500'">
              {{ typeStatistics.PRD }}
            </span>
          </button>

          <button
            type="button"
            class="px-2.5 py-1 text-xs font-medium rounded-md transition-all flex items-center gap-1.5"
            :class="selectedType === 'PROTOTYPE'
              ? 'bg-orange-500 text-white shadow-2xs'
              : 'bg-white text-slate-600 border border-slate-200 hover:border-slate-300 hover:bg-slate-50'"
            @click="selectedType = 'PROTOTYPE'"
          >
            <span class="w-1.5 h-1.5 rounded-full bg-orange-400 inline-block" />
            <span>交互原型</span>
            <span class="px-1.5 py-0.2 rounded-full text-[10px]" :class="selectedType === 'PROTOTYPE' ? 'bg-orange-600 text-white' : 'bg-slate-100 text-slate-500'">
              {{ typeStatistics.PROTOTYPE }}
            </span>
          </button>

          <button
            type="button"
            class="px-2.5 py-1 text-xs font-medium rounded-md transition-all flex items-center gap-1.5"
            :class="selectedType === 'REVIEW'
              ? 'bg-emerald-600 text-white shadow-2xs'
              : 'bg-white text-slate-600 border border-slate-200 hover:border-slate-300 hover:bg-slate-50'"
            @click="selectedType = 'REVIEW'"
          >
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-400 inline-block" />
            <span>审查报告</span>
            <span class="px-1.5 py-0.2 rounded-full text-[10px]" :class="selectedType === 'REVIEW' ? 'bg-emerald-700 text-white' : 'bg-slate-100 text-slate-500'">
              {{ typeStatistics.REVIEW }}
            </span>
          </button>
        </div>

        <!-- 展开/折叠全部：现代高品质胶囊按键组 -->
        <div class="expand-collapse-group">
          <button
            type="button"
            class="expand-pill-btn"
            @click="expandAll"
            title="展开所有时间节点"
          >
            <el-icon :size="13"><Expand /></el-icon>
            <span>全部展开</span>
          </button>
          <span class="group-v-divider" />
          <button
            type="button"
            class="expand-pill-btn"
            @click="collapseAll"
            title="收起所有时间节点"
          >
            <el-icon :size="13"><Fold /></el-icon>
            <span>全部折叠</span>
          </button>
        </div>
      </div>

      <!-- 下行：时间切片过滤与粒度切换（统一 30px 标准控件高度） -->
      <div class="flex flex-wrap items-center justify-between gap-3 pt-2">
        <div class="flex flex-wrap items-center gap-1.5">
          <span class="text-xs font-medium text-slate-400 mr-1 flex items-center gap-1">
            <el-icon :size="13"><Clock /></el-icon>
            <span>时间范围:</span>
          </span>
          <button
            type="button"
            class="filter-time-pill"
            :class="{ active: selectedTimePreset === 'ALL' }"
            @click="selectedTimePreset = 'ALL'; customDateRange = null"
          >
            全部时间
          </button>
          <button
            type="button"
            class="filter-time-pill"
            :class="{ active: selectedTimePreset === 'TODAY' }"
            @click="selectedTimePreset = 'TODAY'; customDateRange = null"
          >
            今天
          </button>
          <button
            type="button"
            class="filter-time-pill"
            :class="{ active: selectedTimePreset === '3DAYS' }"
            @click="selectedTimePreset = '3DAYS'; customDateRange = null"
          >
            近 3 天
          </button>
          <button
            type="button"
            class="filter-time-pill"
            :class="{ active: selectedTimePreset === '7DAYS' }"
            @click="selectedTimePreset = '7DAYS'; customDateRange = null"
          >
            近 7 天
          </button>

          <!-- 精致微型日期范围选择器 -->
          <div class="custom-date-picker-wrap">
            <el-date-picker
              v-model="customDateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              size="small"
              class="refined-date-picker"
              @change="(val: any) => { if (val) selectedTimePreset = 'CUSTOM'; else selectedTimePreset = 'ALL'; }"
            />
          </div>
        </div>

        <!-- 粒度分段控制器（与系统视图切换器同级设计） -->
        <div class="granularity-segmented-control">
          <span class="granularity-label">
            <el-icon :size="12"><Calendar /></el-icon>
            <span>分组:</span>
          </span>
          <button
            type="button"
            class="granularity-btn"
            :class="{ active: timeGranularity === 'day' }"
            @click="timeGranularity = 'day'"
            title="最近 14 天逐日归档"
          >
            <span>按天细分</span>
          </button>
          <button
            type="button"
            class="granularity-btn"
            :class="{ active: timeGranularity === 'month' }"
            @click="timeGranularity = 'month'"
            title="按自然月份汇总"
          >
            <span>按月归档</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 主展示区：加载中或内容呈现 -->
    <div v-loading="loading" class="min-h-[300px]">
      <!-- 空状态 -->
      <div v-if="timeSections.length === 0 && !loading" class="text-center py-20 bg-white rounded-2xl border border-slate-200 shadow-xs">
        <el-icon :size="48" class="text-slate-300 mb-3"><Folder /></el-icon>
        <h4 class="text-base font-semibold text-slate-800 m-0">暂无符合条件的文档</h4>
        <p class="text-xs text-slate-400 mt-1 mb-0">可尝试更换搜索关键词或重置业务类型过滤</p>
      </div>

      <!-- ================= 模式一：树形抽屉分层 (Tree Hierarchy) ================= -->
      <div v-else-if="displayLayout === 'tree'" class="space-y-4">
        <div
          v-for="section in timeSections"
          :key="section.id"
          class="bg-white rounded-xl border border-slate-200/90 shadow-xs overflow-hidden transition-all"
        >
          <!-- 时间分层包 Header -->
          <div
            class="flex items-center justify-between px-4 py-3 cursor-pointer select-none bg-slate-50/60 hover:bg-slate-100/70 border-b transition-colors"
            :class="isNodeOpen(section.id) ? 'border-slate-200/80' : 'border-transparent'"
            @click="toggleNode(section.id)"
          >
            <div class="flex items-center gap-2.5">
              <el-icon
                class="text-slate-400 text-xs transition-transform duration-200"
                :class="{ 'rotate-90': isNodeOpen(section.id) }"
              >
                <ArrowRight />
              </el-icon>

              <!-- 时间包图标（精简尺寸与柔和配色） -->
              <div
                class="w-6 h-6 rounded-md flex items-center justify-center text-xs"
                :class="section.isRecent ? 'bg-orange-50 text-orange-500' : 'bg-slate-100 text-slate-500'"
              >
                <el-icon size="14">
                  <FolderOpened v-if="isNodeOpen(section.id)" />
                  <Folder v-else />
                </el-icon>
              </div>

              <!-- 时间标题与副标 -->
              <div class="flex items-baseline gap-2">
                <span class="text-sm font-semibold text-slate-800">{{ section.title }}</span>
                <span class="text-xs text-slate-400 hidden sm:inline">{{ section.subtitle }}</span>
              </div>
            </div>

            <!-- 时间包内的业务类型小计微胶囊 -->
            <div class="flex items-center gap-1.5 text-xs">
              <span v-if="section.typeCounts.PRD > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] bg-slate-100 text-slate-600">
                <span class="w-1 h-1 rounded-full bg-slate-400" />
                PRD {{ section.typeCounts.PRD }}
              </span>
              <span v-if="section.typeCounts.PROTOTYPE > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] bg-orange-50 text-orange-600">
                <span class="w-1 h-1 rounded-full bg-orange-400" />
                原型 {{ section.typeCounts.PROTOTYPE }}
              </span>
              <span v-if="section.typeCounts.REVIEW > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] bg-emerald-50 text-emerald-600">
                <span class="w-1 h-1 rounded-full bg-emerald-400" />
                审查 {{ section.typeCounts.REVIEW }}
              </span>
              <span class="text-[11px] text-slate-400 font-medium bg-slate-100 px-2 py-0.5 rounded-full ml-1">
                {{ section.items.length }} 篇
              </span>
            </div>
          </div>

          <!-- 展开的内容列表 -->
          <div v-show="isNodeOpen(section.id)" class="divide-y divide-slate-100">
            <div
              v-for="doc in section.items"
              :key="doc.docType + doc.id"
              class="flex flex-col sm:flex-row sm:items-center gap-3 px-4 py-2.5 sm:pl-10 hover:bg-slate-50/70 transition-colors cursor-pointer group"
              @click="goDetail(doc)"
            >
              <!-- 业务类型徽章：精致柔和小巧、与文字协调 -->
              <div class="flex items-center gap-2 flex-shrink-0">
                <span
                  class="inline-flex items-center gap-1 px-2 py-0.5 rounded border text-[11px] font-medium leading-none"
                  :class="[getDocTypeMeta(doc.docType).bg, getDocTypeMeta(doc.docType).text, getDocTypeMeta(doc.docType).border]"
                >
                  <span class="w-1.5 h-1.5 rounded-full" :class="getDocTypeMeta(doc.docType).dot" />
                  <span>{{ getDocTypeMeta(doc.docType).label }}</span>
                </span>
                <span class="text-xs text-slate-400 font-mono w-10">{{ formatTime(doc.createdAt) }}</span>
              </div>

              <!-- 文档标题与描述 -->
              <div class="min-w-0 flex-1">
                <div class="text-sm font-medium text-slate-800 group-hover:text-orange-600 transition-colors truncate">
                  {{ doc.title }}
                </div>
                <div v-if="doc.description" class="text-xs text-slate-400 truncate mt-0.5">
                  {{ doc.description }}
                </div>
              </div>

              <!-- 操作按钮组（升级为全站统一的精致微胶囊操作按钮） -->
              <div class="flex items-center gap-1.5 flex-shrink-0 pt-1 sm:pt-0" @click.stop>
                <button
                  type="button"
                  class="action-pill-btn action-pill-btn--primary"
                  @click="goDetail(doc)"
                  title="查看并进入编辑"
                >
                  <span>打开</span>
                </button>

                <button
                  v-if="doc.docType === 'PRD'"
                  type="button"
                  class="action-pill-btn"
                  @click="exportWord(doc)"
                  title="导出 Word 文档"
                >
                  <el-icon :size="12"><Download /></el-icon>
                  <span>导出</span>
                </button>

                <button
                  v-if="doc.docType === 'PROTOTYPE'"
                  type="button"
                  class="action-pill-btn"
                  @click="exportPrototype(doc)"
                  title="导出 HTML 原型"
                >
                  <el-icon :size="12"><Download /></el-icon>
                  <span>导出</span>
                </button>

                <button
                  v-if="doc.docType === 'REVIEW'"
                  type="button"
                  class="action-pill-btn"
                  @click="exportReview(doc)"
                  title="导出审查报告"
                >
                  <el-icon :size="12"><Download /></el-icon>
                  <span>导出</span>
                </button>

                <button
                  v-if="doc.taskId"
                  type="button"
                  class="action-pill-btn action-pill-btn--warning"
                  @click="handleRegenerate(doc)"
                  title="基于旧参数重新提交生成"
                >
                  <el-icon :size="12"><Refresh /></el-icon>
                  <span>重新生成</span>
                </button>

                <button
                  type="button"
                  class="action-pill-btn action-pill-btn--danger"
                  @click="handleDelete(doc)"
                  title="删除此文档"
                >
                  <el-icon :size="12"><Delete /></el-icon>
                  <span>删除</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ================= 模式二：分期卡片墙 (Sectioned Card Grid) ================= -->
      <div v-else class="space-y-5">
        <div
          v-for="section in timeSections"
          :key="section.id"
          class="stage-card-panel bg-white rounded-xl border border-slate-200/90 shadow-xs overflow-hidden transition-all"
        >
          <!-- 时间分期 Header：支持点击整栏折叠/展开，响应全局「全部展开/折叠」 -->
          <div
            class="stage-card-panel-header flex items-center justify-between px-4 py-3 cursor-pointer select-none bg-slate-50/60 hover:bg-slate-100/70 border-b transition-colors"
            :class="isNodeOpen(section.id) ? 'border-slate-200/80' : 'border-transparent'"
            @click="toggleNode(section.id)"
          >
            <div class="flex items-center gap-2.5">
              <el-icon
                class="text-slate-400 text-xs transition-transform duration-200"
                :class="{ 'rotate-90': isNodeOpen(section.id) }"
              >
                <ArrowRight />
              </el-icon>

              <!-- 日历/分期时间图标（统一精致规格与配色） -->
              <div
                class="w-6 h-6 rounded-md flex items-center justify-center text-xs"
                :class="section.isRecent ? 'bg-blue-50 text-blue-600' : 'bg-slate-100 text-slate-500'"
              >
                <el-icon size="14">
                  <Calendar v-if="timeGranularity === 'day'" />
                  <Folder v-else />
                </el-icon>
              </div>

              <!-- 时间标题与副标 -->
              <div class="flex items-baseline gap-2">
                <span class="text-sm font-semibold text-slate-800">{{ section.title }}</span>
                <span class="text-xs text-slate-400 hidden sm:inline">{{ section.subtitle }}</span>
              </div>
            </div>

            <!-- 时间包内的业务类型小计微胶囊与展开状态 -->
            <div class="flex items-center gap-1.5 text-xs">
              <span v-if="section.typeCounts.PRD > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] bg-blue-50 text-blue-600 border border-blue-100/60">
                <span class="w-1 h-1 rounded-full bg-blue-500" />
                PRD {{ section.typeCounts.PRD }}
              </span>
              <span v-if="section.typeCounts.PROTOTYPE > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] bg-orange-50 text-orange-600 border border-orange-100/60">
                <span class="w-1 h-1 rounded-full bg-orange-400" />
                原型 {{ section.typeCounts.PROTOTYPE }}
              </span>
              <span v-if="section.typeCounts.REVIEW > 0" class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] bg-emerald-50 text-emerald-600 border border-emerald-100/60">
                <span class="w-1 h-1 rounded-full bg-emerald-400" />
                审查 {{ section.typeCounts.REVIEW }}
              </span>
              <span class="text-[11px] text-slate-400 font-medium bg-slate-100 px-2 py-0.5 rounded-full ml-1">
                {{ section.items.length }} 篇
              </span>
              <span class="text-[11px] text-slate-400 ml-1 hidden md:inline">
                {{ isNodeOpen(section.id) ? '收起' : '展开' }}
              </span>
            </div>
          </div>

          <!-- 卡片网格：随折叠状态显示/隐藏 -->
          <div v-show="isNodeOpen(section.id)" class="p-4 bg-slate-50/30">
            <div class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
              <div
                v-for="doc in section.items"
                :key="doc.docType + doc.id"
                class="card-item-box bg-white rounded-xl border border-slate-200/90 hover:border-blue-400/80 hover:shadow-md p-4 transition-all duration-200 cursor-pointer flex flex-col justify-between group relative"
                @click="goDetail(doc)"
              >
                <div>
                  <!-- 顶部类型指示与完整日期时间 -->
                  <div class="flex items-center justify-between gap-2 mb-2.5">
                    <span
                      class="inline-flex items-center gap-1 px-2 py-0.5 rounded border text-[11px] font-medium leading-none"
                      :class="[getDocTypeMeta(doc.docType).bg, getDocTypeMeta(doc.docType).text, getDocTypeMeta(doc.docType).border]"
                    >
                      <span class="w-1.5 h-1.5 rounded-full" :class="getDocTypeMeta(doc.docType).dot" />
                      <span>{{ getDocTypeMeta(doc.docType).label }}</span>
                    </span>
                    <span class="text-[11px] text-slate-400 font-mono flex items-center gap-1">
                      <el-icon :size="11"><Clock /></el-icon>
                      <span>{{ formatShortDate(doc.createdAt) }}</span>
                    </span>
                  </div>

                  <!-- 文档标题 -->
                  <h4 class="text-sm font-semibold text-slate-800 group-hover:text-blue-600 transition-colors line-clamp-1 mb-1.5">
                    {{ doc.title }}
                  </h4>
                  <!-- 文档描述 -->
                  <p class="text-xs text-slate-500 line-clamp-2 leading-relaxed mb-3">
                    {{ doc.description || '暂无详细描述' }}
                  </p>
                </div>

                <!-- 底部操作与微按键组（统一全站微胶囊高质感规范） -->
                <div class="flex items-center justify-between pt-3 border-t border-slate-100 text-xs" @click.stop>
                  <button
                    type="button"
                    class="action-pill-btn action-pill-btn--primary"
                    @click="goDetail(doc)"
                    title="查看并进入编辑"
                  >
                    <span>打开</span>
                    <el-icon :size="11"><ArrowRight /></el-icon>
                  </button>

                  <!-- 快捷操作按钮组 -->
                  <div class="flex items-center gap-1.5">
                    <button
                      v-if="doc.docType === 'PRD'"
                      type="button"
                      class="action-pill-btn"
                      title="导出 Word 文档"
                      @click="exportWord(doc)"
                    >
                      <el-icon :size="12"><Download /></el-icon>
                      <span>导出</span>
                    </button>
                    <button
                      v-if="doc.docType === 'PROTOTYPE'"
                      type="button"
                      class="action-pill-btn"
                      title="导出 HTML 原型"
                      @click="exportPrototype(doc)"
                    >
                      <el-icon :size="12"><Download /></el-icon>
                      <span>导出</span>
                    </button>
                    <button
                      v-if="doc.docType === 'REVIEW'"
                      type="button"
                      class="action-pill-btn"
                      title="导出审查报告"
                      @click="exportReview(doc)"
                    >
                      <el-icon :size="12"><Download /></el-icon>
                      <span>导出</span>
                    </button>

                    <button
                      v-if="doc.taskId"
                      type="button"
                      class="action-icon-pill-btn action-icon-pill-btn--warning"
                      title="基于原有参数重新生成"
                      @click="handleRegenerate(doc)"
                    >
                      <el-icon :size="12"><Refresh /></el-icon>
                    </button>

                    <button
                      type="button"
                      class="action-icon-pill-btn action-icon-pill-btn--danger"
                      title="删除此文档"
                      @click="handleDelete(doc)"
                    >
                      <el-icon :size="12"><Delete /></el-icon>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shadow-2xs {
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

/* 统一搜索框高度为 36px */
.custom-search-input :deep(.el-input__wrapper) {
  height: 36px;
  line-height: 36px;
  border-radius: 8px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  background-color: #ffffff;
  transition: all 0.15s ease;
}
.custom-search-input :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #cbd5e1 inset;
}
.custom-search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px #2563eb inset, 0 0 0 3px rgba(37, 99, 235, 0.1) !important;
}

/* 视图分段选择器（严格 36px 高度与 1:1 对齐） */
.view-mode-switcher {
  display: inline-flex;
  align-items: center;
  height: 36px;
  padding: 3px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  gap: 2px;
  box-sizing: border-box;
}

.switcher-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
  user-select: none;
  white-space: nowrap;
}
.switcher-btn:hover:not(.active) {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.5);
}
.switcher-btn.active {
  background: #ffffff;
  color: #2563eb;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

/* 精致微胶囊操作按钮（与其他页面统一，告别原生粗糙感） */
.action-pill-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 9px;
  height: 26px;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 500;
  color: #475569;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.action-pill-btn:hover {
  background: #ffffff;
  color: #0f172a;
  border-color: #cbd5e1;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

/* 主操作：打开 */
.action-pill-btn--primary {
  color: #2563eb;
  background: #eff6ff;
  border-color: #dbeafe;
}
.action-pill-btn--primary:hover {
  background: #2563eb;
  color: #ffffff;
  border-color: #2563eb;
  box-shadow: 0 2px 5px rgba(37, 99, 235, 0.25);
}

/* 重新生成 */
.action-pill-btn--warning {
  color: #d97706;
  background: #fffbeb;
  border-color: #fef3c7;
}
.action-pill-btn--warning:hover {
  background: #d97706;
  color: #ffffff;
  border-color: #d97706;
}

/* 删除 */
.action-pill-btn--danger {
  color: #94a3b8;
  background: transparent;
  border-color: transparent;
}
.action-pill-btn--danger:hover {
  color: #dc2626;
  background: #fef2f2;
  border-color: #fee2e2;
}

/* ====== 展开/折叠全部：现代微型胶囊组合控制器 ====== */
.expand-collapse-group {
  display: inline-flex;
  align-items: center;
  height: 28px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 0 2px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}
.expand-pill-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: #64748b;
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.expand-pill-btn:hover {
  color: #2563eb;
  background: #eff6ff;
}
.group-v-divider {
  width: 1px;
  height: 12px;
  background: #e2e8f0;
  margin: 0 2px;
}

/* ====== 时间切片胶囊按键 ====== */
.filter-time-pill {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 500;
  color: #475569;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.filter-time-pill:hover:not(.active) {
  background: #f8fafc;
  color: #0f172a;
  border-color: #cbd5e1;
}
.filter-time-pill.active {
  background: #2563eb;
  color: #ffffff;
  border-color: #2563eb;
  box-shadow: 0 1px 3px rgba(37, 99, 235, 0.25);
  font-weight: 600;
}

/* 精致日期选择器定制 */
.custom-date-picker-wrap :deep(.el-input__wrapper) {
  height: 28px !important;
  line-height: 28px !important;
  border-radius: 6px !important;
  box-shadow: 0 0 0 1px #e2e8f0 inset !important;
  padding: 0 8px !important;
}
.custom-date-picker-wrap :deep(.el-range__icon),
.custom-date-picker-wrap :deep(.el-range-separator),
.custom-date-picker-wrap :deep(.el-range-input) {
  font-size: 11.5px !important;
  height: 26px !important;
  line-height: 26px !important;
}

/* ====== 粒度分段控制器（高规格标准组件） ====== */
.granularity-segmented-control {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 2px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  gap: 2px;
  box-sizing: border-box;
}
.granularity-label {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: #94a3b8;
  padding: 0 4px 0 6px;
  user-select: none;
}
.granularity-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  padding: 0 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: #64748b;
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
  user-select: none;
  white-space: nowrap;
}
.granularity-btn:hover:not(.active) {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.6);
}
.granularity-btn.active {
  background: #ffffff;
  color: #2563eb;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

/* ====== 分期卡片模式下的日期独立面板容器 ====== */
.stage-card-panel {
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid rgba(226, 232, 240, 0.9);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
  overflow: hidden;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.stage-card-panel:hover {
  border-color: rgba(203, 213, 225, 0.95);
}

/* 卡片单体盒子（平滑浮起微阴影与边框过渡） */
.card-item-box {
  background: #ffffff;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}
.card-item-box:hover {
  border-color: #93c5fd;
  box-shadow: 0 6px 16px -2px rgba(37, 99, 235, 0.08), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-2px);
}

/* 精致图标微胶囊按键（与 action-pill-btn 统一规格） */
.action-icon-pill-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}
.action-icon-pill-btn:hover {
  color: #2563eb;
  border-color: #cbd5e1;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  transform: translateY(-1px);
}
.action-icon-pill-btn:active {
  transform: translateY(0);
}

.action-icon-pill-btn--warning {
  color: #d97706;
  background: #fffbeb;
  border-color: #fef3c7;
}
.action-icon-pill-btn--warning:hover {
  background: #f59e0b;
  color: #ffffff;
  border-color: #f59e0b;
  box-shadow: 0 2px 5px rgba(245, 158, 11, 0.25);
}

.action-icon-pill-btn--danger {
  color: #ef4444;
  background: #fef2f2;
  border-color: #fee2e2;
}
.action-icon-pill-btn--danger:hover {
  background: #ef4444;
  color: #ffffff;
  border-color: #ef4444;
  box-shadow: 0 2px 5px rgba(239, 68, 68, 0.25);
}
</style>
