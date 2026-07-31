<script setup lang="ts">
/**
 * PRD 结果页 —— 专业编辑器布局
 * 左：大纲导航（层级 / 高亮 / 跳转 / 新增）
 * 右：章节结构化编辑 + 轻量富文本
 */
import { onMounted, onBeforeUnmount, ref, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft, Check, Download, Refresh, Plus, Delete, MagicStick,
} from '@element-plus/icons-vue'
import client from '@/api/client'
import { getTaskById, getTaskSseUrl } from '@/api/task'
import RichTextEditor from '@/components/common/RichTextEditor.vue'
import MarkdownContent from '@/components/common/MarkdownContent.vue'
import ChartSection from '@/components/common/ChartSection.vue'
import { hasMermaid as detectMermaid, extractMermaidCode, mermaidCodeToPngBase64 } from '@/utils/mermaid'

interface Chapter {
  title: string
  content: string
  type?: string
}
interface PrdState {
  title: string
  summary: string
  chapters: Chapter[]
}

const route = useRoute()
const router = useRouter()
const id = computed(() => Number(route.params.id))

const title = ref('')
const description = ref('')
const summary = ref('')
const chapters = ref<Chapter[]>([])
const taskId = ref<number | null>(null)
const templateType = ref('STANDARD')

const loading = ref(true)
const saving = ref(false)
const dirty = ref(false)

const activeKey = ref<string>('summary') // 'summary' | 'chapter-N'
const contentScrollRef = ref<HTMLElement | null>(null)
const outlineScrollRef = ref<HTMLElement | null>(null)

// 比较模式
const compare = ref<PrdState | null>(null)
const compareLoading = ref(false)

// 审查问题侧栏（fromReview）
interface ReviewIssue {
  severity: string
  dimension: string
  chapterIndex?: number
  chapterTitle?: string
  originalIndex?: number
  location: string
  targetText?: string
  description: string
  suggestion: string
}
const reviewIssues = ref<ReviewIssue[]>([])
const reviewScore = ref<number | null>(null)
const reviewSummary = ref('')
const reviewTotalIssueCount = ref(0)
const reviewFilteredFixedCount = ref(0)
const fixedFocusIssue = ref<ReviewIssue | null>(null)
const reviewPanelOpen = ref(true)
const reviewFixing = ref(false)
const reviewFixTarget = ref<'batch' | number | null>(null)
const reviewInlineFixing = ref<number | null>(null)
const fromReviewId = computed(() => {
  const v = route.query.fromReview
  return v ? Number(v) : null
})
const fixedFromReviewId = computed(() => {
  const v = route.query.fixedFromReview
  return v ? Number(v) : null
})
const highlightIssueIndex = computed(() => {
  const v = route.query.issue
  return v != null && v !== '' ? Number(v) : null
})
const fixedReviewIssueIndexes = computed(() => parseIssueIndexesQuery(route.query.fixedIssues))
const focusReviewIssueIndex = computed(() => {
  const v = route.query.focusIssue
  return v != null && v !== '' ? Number(v) : null
})
const highlightIssueDisplayIndex = computed(() => {
  const originalIndex = highlightIssueIndex.value
  if (originalIndex == null || Number.isNaN(originalIndex)) return null
  const displayIndex = reviewIssues.value.findIndex((issue, idx) =>
    reviewIssueOriginalIndex(issue, idx) === originalIndex,
  )
  return displayIndex >= 0 ? displayIndex : null
})
const reviewSideTitle = computed(() =>
  reviewFilteredFixedCount.value > 0 ? '剩余审查问题' : '审查问题',
)
const reviewCriticalMajorCount = computed(() =>
  reviewIssues.value.filter(i => i.severity === 'CRITICAL' || i.severity === 'MAJOR').length,
)
let reviewFixEventSource: EventSource | null = null

// 新增章节弹窗
const addDialogVisible = ref(false)
const newChapterTitle = ref('')

// ── 大纲节点 ─────────────────────────────────────────────
interface OutlineNode {
  key: string
  title: string
  level: number
  index?: number // chapter index
  kind: 'summary' | 'chapter'
}

function headingLevel(titleText: string): number {
  if (!titleText) return 1
  const m = titleText.trim().match(/^(\d+(?:\.\d+)*)/)
  if (m) {
    const dots = (m[1].match(/\./g) || []).length
    return Math.min(dots + 1, 3)
  }
  // 中文编号
  if (/^[一二三四五六七八九十]+[、.．]/.test(titleText.trim())) return 1
  return 1
}

const outlineNodes = computed<OutlineNode[]>(() => {
  const nodes: OutlineNode[] = [
    { key: 'summary', title: '一句话需求', level: 1, kind: 'summary' },
  ]
  chapters.value.forEach((ch, i) => {
    nodes.push({
      key: `chapter-${i}`,
      title: ch.title || `未命名章节 ${i + 1}`,
      level: headingLevel(ch.title || ''),
      index: i,
      kind: 'chapter',
    })
  })
  return nodes
})

// ── 数据加载 ─────────────────────────────────────────────
function parseContent(raw: any): PrdState {
  let value = raw
  for (let i = 0; i < 3 && typeof value === 'string'; i++) {
    try { value = JSON.parse(value) } catch { break }
  }
  return {
    title: value?.title || '',
    summary: value?.summary || '',
    chapters: Array.isArray(value?.chapters)
      ? value.chapters.map((c: any) => ({
          title: c?.title || '未命名章节',
          content: c?.content || '',
          type: c?.type,
        }))
      : [],
  }
}

async function loadPrd(targetId: number) {
  const response = await client.get(`/prd/${targetId}`)
  return response.data.data
}

async function load() {
  loading.value = true
  dirty.value = false
  try {
    const data = await loadPrd(id.value)
    const parsed = parseContent(data.content)
    title.value = data.title || parsed.title
    description.value = data.description || ''
    summary.value = parsed.summary
    chapters.value = parsed.chapters
    taskId.value = data.taskId ?? null
    templateType.value = data.template || 'STANDARD'

    const compareId = Number(route.query.compare)
    if (compareId && compareId !== id.value) {
      compareLoading.value = true
      try {
        const cData = await loadPrd(compareId)
        compare.value = parseContent(cData.content)
        if (cData.title) compare.value.title = cData.title
      } finally {
        compareLoading.value = false
      }
    } else {
      compare.value = null
    }

    // 从审查页进入或修复后继续处理时，加载报告中尚未被本次流程处理的问题。
    if (fromReviewId.value) {
      await loadReviewIssues(fromReviewId.value)
    } else {
      clearReviewIssues()
    }

    // 默认选中 summary 或第一章
    activeKey.value = chapters.value.length ? 'chapter-0' : 'summary'
  } catch {
    ElMessage.error('PRD 加载失败')
  } finally {
    loading.value = false
    await focusRouteIssueAfterRender()
  }
}

async function loadReviewIssues(reportId: number) {
  try {
    const res = await client.get(`/review/${reportId}`)
    const data = res.data.data
    let parsed: any = data.issues || '{}'
    for (let i = 0; i < 3 && typeof parsed === 'string'; i++) {
      try { parsed = JSON.parse(parsed) } catch { break }
    }
    reviewSummary.value = parsed.summary || ''
    reviewScore.value = typeof parsed.score === 'number' ? parsed.score : null
    const allIssues: ReviewIssue[] = Array.isArray(parsed.issues)
      ? parsed.issues.map((issue: ReviewIssue, originalIndex: number) => ({ ...issue, originalIndex }))
      : []
    const fixedIndexes = fixedReviewIssueIndexes.value
    const focusOriginalIndex = focusReviewIssueIndex.value
    fixedFocusIssue.value = null
    reviewTotalIssueCount.value = allIssues.length
    reviewFilteredFixedCount.value = 0
    if (focusOriginalIndex != null && !Number.isNaN(focusOriginalIndex)) {
      fixedFocusIssue.value = allIssues.find((issue, idx) =>
        reviewIssueOriginalIndex(issue, idx) === focusOriginalIndex,
      ) || null
    } else if (fixedIndexes.size === 1) {
      const onlyFixedIndex = Array.from(fixedIndexes)[0]
      fixedFocusIssue.value = allIssues.find((issue, idx) =>
        reviewIssueOriginalIndex(issue, idx) === onlyFixedIndex,
      ) || null
    }
    reviewIssues.value = allIssues.filter((issue, idx) => {
      const originalIndex = reviewIssueOriginalIndex(issue, idx)
      const fixed = fixedIndexes.has(originalIndex)
      if (fixed) reviewFilteredFixedCount.value += 1
      return !fixed
    })
    reviewPanelOpen.value = true
  } catch {
    clearReviewIssues()
  }
}

function clearReviewIssues() {
  reviewIssues.value = []
  reviewScore.value = null
  reviewSummary.value = ''
  reviewTotalIssueCount.value = 0
  reviewFilteredFixedCount.value = 0
  fixedFocusIssue.value = null
}

function parseIssueIndexesQuery(value: unknown): Set<number> {
  const rawValues = Array.isArray(value) ? value : [value]
  const indexes = rawValues
    .filter((v): v is string => typeof v === 'string' && v.trim().length > 0)
    .flatMap(v => v.split(','))
    .map(v => Number(v.trim()))
    .filter(v => Number.isInteger(v) && v >= 0)
  return new Set(indexes)
}

function reviewIssueOriginalIndex(issue: ReviewIssue, fallbackIndex: number): number {
  return Number.isInteger(issue.originalIndex) ? issue.originalIndex! : fallbackIndex
}

function normalizeAnchorText(value: string | undefined | null): string {
  return (value || '')
    .toLowerCase()
    .replace(/[\s#*_`~\-—–.,，。、:：;；!！?？()[\]（）【】{}<>《》"“”'‘’|/\\]+/g, '')
}

/** 按标题/位置文本模糊匹配章节 */
function matchChapterIndex(location: string | undefined | null): number {
  const loc = normalizeAnchorText(location)
  if (!loc) return -1
  let best = -1
  let bestScore = 0
  chapters.value.forEach((ch, i) => {
    const t = normalizeAnchorText(ch.title)
    if (!t) return
    if (loc.includes(t) || t.includes(loc)) {
      const score = Math.min(t.length, loc.length)
      if (score > bestScore) { bestScore = score; best = i }
      return
    }
    // 取标题中数字编号部分
    const num = (ch.title || '').match(/\d+(\.\d+)*/)?.[0]
    if (num && loc.includes(num.replace(/\./g, ''))) {
      if (num.length + 1 > bestScore) { bestScore = num.length + 1; best = i }
    }
  })
  return best
}

function resolveIssueChapterIndex(issue: ReviewIssue): number {
  const byTitle = matchChapterIndex(issue.chapterTitle)
  if (byTitle >= 0) return byTitle

  if (Number.isInteger(issue.chapterIndex)
    && issue.chapterIndex! >= 0
    && issue.chapterIndex! < chapters.value.length) {
    return issue.chapterIndex!
  }

  return matchChapterIndex(issue.location)
}

async function jumpToIssue(issueIndex: number) {
  const issue = reviewIssues.value[issueIndex]
  if (!issue) return
  await jumpToIssueData(issue)
}

async function jumpToIssueData(issue: ReviewIssue, successMessage?: string) {
  const chIdx = resolveIssueChapterIndex(issue)
  if (chIdx >= 0) {
    const ok = await selectNode({
      key: `chapter-${chIdx}`,
      title: chapters.value[chIdx].title,
      level: headingLevel(chapters.value[chIdx].title),
      index: chIdx,
      kind: 'chapter',
    }, issue)
    if (successMessage && ok) ElMessage.success(successMessage)
  } else {
    ElMessage.info('未能自动定位章节，请在大纲中手动选择')
  }
}

async function focusRouteIssueAfterRender() {
  await nextTick()
  await new Promise(resolve => window.requestAnimationFrame(resolve))
  await nextTick()

  const issue = highlightIssueDisplayIndex.value != null
    ? reviewIssues.value[highlightIssueDisplayIndex.value]
    : fixedFocusIssue.value
  if (!issue) return

  const chIdx = resolveIssueChapterIndex(issue)
  if (chIdx < 0) {
    ElMessage.info('未能自动定位章节，请在大纲中手动选择')
    return
  }
  const node: OutlineNode = {
    key: `chapter-${chIdx}`,
    title: chapters.value[chIdx].title,
    level: headingLevel(chapters.value[chIdx].title),
    index: chIdx,
    kind: 'chapter',
  }

  for (let i = 0; i < 5; i++) {
    if (await selectNode(node, issue)) {
      if (fixedFocusIssue.value === issue) ElMessage.success('已定位到本次修复的章节')
      return
    }
    await delay(120)
  }
}

function delay(ms: number) {
  return new Promise(resolve => window.setTimeout(resolve, ms))
}

function severityMeta(sev: string) {
  const map: Record<string, { label: string; color: string; bg: string }> = {
    CRITICAL: { label: '严重', color: '#cf2d56', bg: 'rgba(207,45,86,0.08)' },
    MAJOR: { label: '重要', color: '#c08532', bg: 'rgba(192,133,50,0.08)' },
    MINOR: { label: '轻微', color: 'rgba(38,37,30,0.55)', bg: 'rgba(38,37,30,0.05)' },
    SUGGESTION: { label: '建议', color: '#f54e00', bg: 'rgba(245,78,0,0.06)' },
  }
  return map[sev] || { label: sev, color: 'rgba(38,37,30,0.55)', bg: 'rgba(38,37,30,0.04)' }
}

/** 章节是否含有可渲染的 Mermaid 图 */
function hasMermaid(content: string | undefined): boolean {
  return detectMermaid(content)
}

/** 结构图/流程图：优先用专用图表编辑器（默认渲染图） */
function isChartChapter(chapter: Chapter): boolean {
  if (chapter.type === 'structure' || chapter.type === 'flow') return true
  // 标题兜底识别
  const t = (chapter.title || '')
  if (/结构图|流程图|页面结构|业务流/.test(t)) return true
  return hasMermaid(chapter.content)
}

// ── 保存 ─────────────────────────────────────────────────
async function save() {
  saving.value = true
  try {
    await client.put(`/prd/${id.value}`, {
      title: title.value,
      description: description.value,
      content: {
        title: title.value,
        summary: summary.value,
        chapters: chapters.value,
      },
    })
    dirty.value = false
    ElMessage.success('已保存')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

function markDirty() {
  dirty.value = true
}

// ── 大纲点击 → 滚动定位 ──────────────────────────────────
async function selectNode(node: OutlineNode, issue?: ReviewIssue): Promise<boolean> {
  activeKey.value = node.key
  await nextTick()
  const section = document.getElementById(`prd-section-${node.key}`) as HTMLElement | null
  const target = issue && section ? findIssueTargetElement(section, issue) : section
  return scrollElementIntoContent(target || section, section || undefined, Boolean(issue))
}

function scrollElementIntoContent(target?: HTMLElement | null, section?: HTMLElement, precise = false): boolean {
  const container = contentScrollRef.value
  if (!target || !container) return false
  const containerRect = container.getBoundingClientRect()
  const targetRect = target.getBoundingClientRect()
  const top = container.scrollTop + targetRect.top - containerRect.top - (precise ? 28 : 12)
  container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })

  const flashTarget = precise && target !== section ? target : section || target
  flashTarget.classList.add(precise && target !== section ? 'issue-focus-flash' : 'section-flash')
  window.setTimeout(() => {
    flashTarget.classList.remove('section-flash')
    flashTarget.classList.remove('issue-focus-flash')
  }, 1100)
  return true
}

function findIssueTargetElement(section: HTMLElement, issue: ReviewIssue): HTMLElement | null {
  // 优先用 targetText 做精准定位
  if (issue.targetText && issue.targetText.length >= 10) {
    const normalizedTarget = normalizeAnchorText(issue.targetText)
    const nodes = Array.from(section.querySelectorAll<HTMLElement>(
      '.rte-body h1,.rte-body h2,.rte-body h3,.rte-body h4,.rte-body p,.rte-body li,.rte-body td,.rte-body th,.rte-body div',
    )).filter(el => (el.innerText || el.textContent || '').trim().length > 0)
    for (const el of nodes) {
      const text = normalizeAnchorText(el.innerText || el.textContent || '')
      if (text.includes(normalizedTarget) || normalizedTarget.includes(text)) {
        return el
      }
    }
    // 尝试在 innerHTML 中搜索 targetText 的前 30 个字符
    const prefix = issue.targetText.substring(0, Math.min(30, issue.targetText.length))
    for (const el of nodes) {
      if ((el.innerHTML || '').includes(prefix)) return el
    }
  }

  const candidates = buildIssueSearchTerms(issue)
  if (!candidates.length) return null
  const nodes = Array.from(section.querySelectorAll<HTMLElement>(
    '.rte-body h1,.rte-body h2,.rte-body h3,.rte-body h4,.rte-body p,.rte-body li,.rte-body td,.rte-body th,.rte-body div',
  )).filter(el => (el.innerText || el.textContent || '').trim().length > 0)

  let best: HTMLElement | null = null
  let bestScore = 0
  for (const el of nodes) {
    const text = normalizeAnchorText(el.innerText || el.textContent || '')
    if (!text) continue
    for (const term of candidates) {
      if (!term || term.length < 4) continue
      let score = 0
      if (text.includes(term)) score = term.length + 20
      else if (term.includes(text) && text.length >= 6) score = text.length + 8
      else score = overlapScore(text, term)
      if (score > bestScore) {
        bestScore = score
        best = el
      }
    }
  }
  return bestScore >= 8 ? best : null
}

function buildIssueSearchTerms(issue: ReviewIssue): string[] {
  const terms = [
    issue.location,
    issue.chapterTitle,
    issue.description,
  ]
  const locationParts = (issue.location || '')
    .split(/[>＞/\\|｜\-—–:：,，、\n]/)
    .map(s => s.trim())
    .filter(Boolean)
  return Array.from(new Set([...terms, ...locationParts]
    .map(normalizeAnchorText)
    .filter(s => s.length >= 4)))
    .sort((a, b) => b.length - a.length)
}

function overlapScore(text: string, term: string): number {
  const max = Math.min(text.length, term.length, 80)
  for (let len = max; len >= 4; len--) {
    for (let start = 0; start + len <= term.length; start++) {
      if (text.includes(term.slice(start, start + len))) return len
    }
  }
  return 0
}

// 滚动时同步大纲高亮（IntersectionObserver）
let observer: IntersectionObserver | null = null

function setupScrollSpy() {
  observer?.disconnect()
  const root = contentScrollRef.value
  if (!root) return
  observer = new IntersectionObserver(
    (entries) => {
      // 取视口内最靠上的可见 section
      const visible = entries
        .filter(e => e.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      if (visible[0]?.target?.id) {
        const key = visible[0].target.id.replace('prd-section-', '')
        if (key) activeKey.value = key
      }
    },
    { root, rootMargin: '-10% 0px -60% 0px', threshold: [0, 0.25, 0.5] },
  )
  root.querySelectorAll('[id^="prd-section-"]').forEach(el => observer!.observe(el))
}

watch([loading, chapters], async () => {
  if (loading.value) return
  await nextTick()
  setupScrollSpy()
})

// ── 章节增删 ─────────────────────────────────────────────
function openAddChapter() {
  newChapterTitle.value = ''
  addDialogVisible.value = true
}

async function confirmAddChapter() {
  const t = newChapterTitle.value.trim()
  if (!t) {
    ElMessage.warning('请输入章节标题')
    return
  }
  chapters.value.push({ title: t, content: '' })
  markDirty()
  addDialogVisible.value = false
  const idx = chapters.value.length - 1
  await nextTick()
  selectNode({ key: `chapter-${idx}`, title: t, level: headingLevel(t), index: idx, kind: 'chapter' })
  ElMessage.success('已新增章节')
}

async function removeChapter(index: number) {
  const name = chapters.value[index]?.title || `章节 ${index + 1}`
  try {
    await ElMessageBox.confirm(
      `确定删除「${name}」吗？删除后需保存才会生效。`,
      '删除章节',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  chapters.value.splice(index, 1)
  markDirty()
  // 修正 activeKey
  if (activeKey.value === `chapter-${index}` || activeKey.value.startsWith('chapter-')) {
    activeKey.value = chapters.value.length
      ? `chapter-${Math.min(index, chapters.value.length - 1)}`
      : 'summary'
  }
  ElMessage.success('已删除章节')
}

// ── 工具栏操作 ───────────────────────────────────────────
async function exportWord() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('有未保存的修改，导出将使用已保存版本。是否先保存？', '导出 Word', {
        confirmButtonText: '保存并导出',
        cancelButtonText: '直接导出',
        distinguishCancelAndClose: true,
        type: 'info',
      })
      await save()
    } catch (e: any) {
      if (e === 'close') return
      // cancel → 直接导出
    }
  }
  try {
    // 收集含 mermaid 的章节，渲染为 PNG 一并导出；失败时仍 POST 空列表，由后端 Kroki 兜底
    const chartImages: { key: string; pngBase64: string }[] = []
    let mermaidCount = 0
    for (let i = 0; i < chapters.value.length; i++) {
      const ch = chapters.value[i]
      if (!hasMermaid(ch.content)) continue
      const code = extractMermaidCode(ch.content)
      if (!code) continue
      mermaidCount++
      try {
        const png = await mermaidCodeToPngBase64(code)
        if (png) {
          chartImages.push({ key: String(i), pngBase64: png })
          // 语义 key：STANDARD 骨架第 2/3 章用
          const t = (ch.type || '').toLowerCase()
          if (t === 'flow' || (ch.title || '').includes('流程')) {
            chartImages.push({ key: 'flow', pngBase64: png })
          }
          if (t === 'structure' || (ch.title || '').includes('结构')) {
            chartImages.push({ key: 'structure', pngBase64: png })
          }
        }
      } catch (err) {
        console.warn('章节图表转 PNG 失败', i, err)
      }
    }
    if (hasMermaid(summary.value)) {
      const code = extractMermaidCode(summary.value)
      if (code) {
        mermaidCount++
        try {
          const png = await mermaidCodeToPngBase64(code)
          if (png) chartImages.push({ key: 'summary', pngBase64: png })
        } catch { /* ignore */ }
      }
    }

    // 始终走 POST：即使前端 0 张图，后端也会从 mermaid 源码自动渲染
    const response = await client.post(
      `/prd/${id.value}/export`,
      { chartImages },
      { responseType: 'blob' },
    )
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${title.value || 'PRD'}.docx`
    anchor.click()
    URL.revokeObjectURL(url)
    if (mermaidCount > 0 && chartImages.length === 0) {
      ElMessage.success('Word 导出成功（图表由服务端渲染）')
    } else if (chartImages.length) {
      ElMessage.success(`Word 导出成功（含 ${chartImages.length} 张图）`)
    } else {
      ElMessage.success('Word 导出成功')
    }
  } catch {
    ElMessage.error('Word 导出失败')
  }
}

async function regenerate() {
  try {
    await ElMessageBox.confirm('将保留当前版本并重新提交生成，是否继续？', '重新生成', { type: 'info' })
    let tid = taskId.value
    if (!tid) {
      const data = await loadPrd(id.value)
      tid = data.taskId
    }
    if (!tid) {
      ElMessage.warning('未找到关联任务，无法重新生成')
      return
    }
    const res = await client.post(`/task/${tid}/regenerate`)
    const newTaskId = res.data.data.taskId
    ElMessage.success(`已提交任务 ${newTaskId}，请稍后在「我的文档」查看`)
  } catch { /* cancelled */ }
}

function useForEnhance() {
  router.push({ path: '/prd/enhance', query: { prdDocumentId: String(id.value) } })
}

function reReview() {
  router.push({ path: '/prd/review', query: { prdDocumentId: String(id.value) } })
}

function resolveReviewFixIssueIndexes(issueIndexes?: number[]): number[] {
  if (issueIndexes?.length) return issueIndexes
  return reviewIssues.value
    .map((issue, idx) => ({ issue, originalIndex: reviewIssueOriginalIndex(issue, idx) }))
    .filter(({ issue }) => issue.severity === 'CRITICAL' || issue.severity === 'MAJOR')
    .map(({ originalIndex }) => originalIndex)
}

function mergeFixedIssueIndexes(nextIndexes: number[]): number[] {
  return Array.from(new Set([...fixedReviewIssueIndexes.value, ...nextIndexes]))
    .sort((a, b) => a - b)
}

// ── 生命周期 ─────────────────────────────────────────────
async function aiFixReview(issueIndexes?: number[]) {
  if (!fromReviewId.value) return
  const selectedIssueIndexes = resolveReviewFixIssueIndexes(issueIndexes)
  const count = selectedIssueIndexes.length
  if (count === 0) {
    ElMessage.warning('没有可修复的严重/重要问题')
    return
  }

  if (dirty.value) {
    try {
      await ElMessageBox.confirm(
        '当前 PRD 有未保存修改。AI 修复会基于已保存内容执行，是否先保存再修复？',
        'AI 修复 PRD',
        { type: 'info', confirmButtonText: '保存并修复', cancelButtonText: '取消' },
      )
      await save()
      if (dirty.value) {
        ElMessage.warning('请先保存成功后再修复')
        return
      }
    } catch {
      return
    }
  } else {
    try {
      await ElMessageBox.confirm(
        issueIndexes
          ? '将只修复该问题对应章节，并生成修订版 PRD（保留当前版本），是否继续？'
          : `将修复 ${count} 条严重/重要问题，生成新版 PRD（保留当前版本），是否继续？`,
        'AI 修复 PRD',
        { type: 'info', confirmButtonText: '开始修复', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }

  reviewFixing.value = true
  reviewFixTarget.value = selectedIssueIndexes.length === 1 ? selectedIssueIndexes[0] : 'batch'
  try {
    const body = {
      issueIndexes: selectedIssueIndexes,
      sourcePrdDocumentId: id.value,
    }
    const res = await client.post(`/review/${fromReviewId.value}/fix`, body)
    watchReviewFixTask(res.data.data.taskId as number, selectedIssueIndexes)
  } catch {
    finishReviewFixing()
  }
}

function finishReviewFixing() {
  reviewFixing.value = false
  reviewFixTarget.value = null
  reviewInlineFixing.value = null
}

async function aiFixInline(issueIndex: number) {
  if (!fromReviewId.value) return
  const issue = reviewIssues.value[issueIndex]
  if (!issue) return

  if (dirty.value) {
    try {
      await ElMessageBox.confirm(
        '当前 PRD 有未保存修改。AI 修复会基于已保存内容执行，是否先保存再修复？',
        'AI 内联修复',
        { type: 'info', confirmButtonText: '保存并修复', cancelButtonText: '取消' },
      )
      await save()
      if (dirty.value) {
        ElMessage.warning('请先保存成功后再修复')
        return
      }
    } catch {
      return
    }
  }

  reviewInlineFixing.value = issueIndex
  try {
    const originalIndex = reviewIssueOriginalIndex(issue, issueIndex)
    const res = await client.post(`/review/${fromReviewId.value}/fix-inline`, {
      issueIndex: originalIndex,
      sourcePrdDocumentId: id.value,
    })
    const data = res.data.data
    const chIdx = data.chapterIndex as number
    const oldText = data.oldText as string
    const newText = data.newText as string
    const changeSummary = data.changeSummary as string
    const patchedContent = data.patchedContent as string
    const newPrdId = data.prdDocumentId as number

    // 原地更新章节内容
    if (chIdx >= 0 && chIdx < chapters.value.length) {
      chapters.value[chIdx].content = patchedContent
      markDirty()
    }

    // 高亮被修改的文字
    await nextTick()
    if (chIdx >= 0) {
      activeKey.value = `chapter-${chIdx}`
      await nextTick()
      const section = document.getElementById(`prd-section-chapter-${chIdx}`) as HTMLElement | null
      if (section && newText) {
        // 在渲染后的 DOM 中查找 newText 并高亮
        const nodes = Array.from(section.querySelectorAll<HTMLElement>(
          '.rte-body p,.rte-body li,.rte-body td,.rte-body th,.rte-body div',
        )).filter(el => (el.innerText || el.textContent || '').trim().length > 0)
        for (const el of nodes) {
          const text = el.innerText || el.textContent || ''
          if (text.includes(newText.substring(0, Math.min(30, newText.length)))) {
            el.classList.add('inline-fix-flash')
            window.setTimeout(() => el.classList.remove('inline-fix-flash'), 2000)
            el.scrollIntoView({ behavior: 'smooth', block: 'center' })
            break
          }
        }
      }
    }

    ElMessage.success(changeSummary || '内联修复完成')

    // 从问题列表中移除已修复的问题
    reviewIssues.value = reviewIssues.value.filter((_, i) => i !== issueIndex)
    reviewFilteredFixedCount.value += 1

    // 保存为新版本
    if (newPrdId) {
      await client.put(`/prd/${id.value}`, {
        title: title.value,
        description: description.value,
        content: {
          title: title.value,
          summary: summary.value,
          chapters: chapters.value,
        },
      })
      dirty.value = false
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '内联修复失败')
  } finally {
    reviewInlineFixing.value = null
  }
}

function watchReviewFixTask(fixTaskId: number, fixedIssueIndexes: number[]) {
  reviewFixEventSource?.close()
  reviewFixEventSource = new EventSource(getTaskSseUrl(fixTaskId))
  reviewFixEventSource.addEventListener('progress', async (e) => {
    const d = JSON.parse((e as MessageEvent).data)
    if (d.progress <= 0 && d.message && String(d.message).includes('失败')) {
      reviewFixEventSource?.close()
      reviewFixEventSource = null
      finishReviewFixing()
      ElMessage.error(d.message)
      return
    }
    if (d.progress >= 100) {
      reviewFixEventSource?.close()
      reviewFixEventSource = null
      await openFixedPrd(fixTaskId, fixedIssueIndexes)
    }
  })
  reviewFixEventSource.onerror = async () => {
    reviewFixEventSource?.close()
    reviewFixEventSource = null
    await openFixedPrd(fixTaskId, fixedIssueIndexes)
  }
}

async function openFixedPrd(fixTaskId: number, fixedIssueIndexes: number[]) {
  try {
    const r = await getTaskById(fixTaskId)
    if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
      finishReviewFixing()
      const mergedFixedIssues = mergeFixedIssueIndexes(fixedIssueIndexes)
      ElMessage.success('修复完成，正在打开新版本')
      router.push({
        path: `/prd/${r.data.data.resultRefId}`,
        query: {
          compare: String(id.value),
          fromReview: String(fromReviewId.value),
          fixedFromReview: String(fromReviewId.value),
          fixedIssues: mergedFixedIssues.join(','),
          ...(fixedIssueIndexes.length === 1 ? { focusIssue: String(fixedIssueIndexes[0]) } : {}),
        },
      })
    } else if (r.data.data.status === 'FAILED') {
      finishReviewFixing()
      ElMessage.error(r.data.data.errorMessage || '修复失败')
    } else {
      finishReviewFixing()
      ElMessage.warning('修复任务仍在处理中，请稍后在我的文档中查看结果')
    }
  } catch {
    finishReviewFixing()
    ElMessage.warning('修复状态获取失败，请稍后在我的文档中查看结果')
  }
}

onMounted(load)
onBeforeUnmount(() => {
  observer?.disconnect()
  reviewFixEventSource?.close()
  finishReviewFixing()
})

// id 变化重新加载
watch(id, () => load())
</script>

<template>
  <div class="prd-page" v-loading="loading">
    <!-- 顶部工具栏 -->
    <header class="prd-toolbar">
      <div class="toolbar-left">
        <el-button link class="back-btn" @click="router.push('/documents')">
          <el-icon><ArrowLeft /></el-icon>
          我的文档
        </el-button>
        <span class="toolbar-divider" />
        <div class="title-wrap">
          <el-input
            v-model="title"
            class="doc-title-input"
            placeholder="PRD 标题"
            @input="markDirty"
          />
          <span class="doc-meta">
            #{{ id }}
            <span v-if="templateType === 'CUSTOM'" class="tag-custom">自定义模板</span>
            <span v-if="dirty" class="tag-dirty">未保存</span>
          </span>
        </div>
      </div>
      <div class="toolbar-actions">
        <el-button :loading="saving" type="primary" @click="save">
          <el-icon><Check /></el-icon>
          保存
        </el-button>
        <el-button @click="exportWord">
          <el-icon><Download /></el-icon>
          导出 Word
        </el-button>
        <el-button @click="regenerate">
          <el-icon><Refresh /></el-icon>
          重新生成
        </el-button>
        <el-button @click="useForEnhance">
          <el-icon><MagicStick /></el-icon>
          用作增强
        </el-button>
        <el-button v-if="fromReviewId || fixedFromReviewId" @click="reReview">
          再审查
        </el-button>
      </div>
    </header>

    <!-- 主体：左大纲 + 右编辑 + 可选审查侧栏 -->
    <div class="prd-workspace" v-if="!loading">
      <!-- 左侧大纲 -->
      <aside class="outline-panel" ref="outlineScrollRef">
        <div class="outline-header">
          <span class="outline-title">文档大纲</span>
          <span class="outline-count">{{ chapters.length }} 章</span>
        </div>

        <nav class="outline-nav">
          <button
            v-for="node in outlineNodes"
            :key="node.key"
            type="button"
            class="outline-item"
            :class="{
              active: activeKey === node.key,
              [`level-${node.level}`]: true,
            }"
            @click="selectNode(node)"
          >
            <span class="outline-dot" />
            <span class="outline-label" :title="node.title">{{ node.title }}</span>
          </button>
        </nav>

        <div class="outline-footer">
          <el-button class="btn-add-chapter" plain @click="openAddChapter">
            <el-icon><Plus /></el-icon>
            新增章节
          </el-button>
        </div>
      </aside>

      <!-- 右侧内容 -->
      <main class="content-panel" ref="contentScrollRef">
        <!-- 描述（可选折叠区） -->
        <section class="doc-desc-block">
          <label class="field-label">文档描述</label>
          <el-input
            v-model="description"
            type="textarea"
            :rows="2"
            placeholder="原始需求描述（可选）"
            maxlength="50000"
            show-word-limit
            @input="markDirty"
          />
        </section>

        <!-- 一句话需求 -->
        <section id="prd-section-summary" class="content-section" :class="{ active: activeKey === 'summary' }">
          <div class="section-head">
            <h2 class="section-title">一句话需求</h2>
          </div>
          <RichTextEditor
            v-model="summary"
            placeholder="用一句话概括本功能的核心价值与目标用户…"
            min-height="100px"
            @update:model-value="markDirty"
            @focus="activeKey = 'summary'"
          />
          <div v-if="hasMermaid(summary)" class="chart-preview">
            <div class="chart-preview-label">图表预览</div>
            <MarkdownContent :content="summary" />
          </div>
        </section>

        <!-- 各章节 -->
        <section
          v-for="(chapter, index) in chapters"
          :id="`prd-section-chapter-${index}`"
          :key="`chapter-${index}-${chapter.title}`"
          class="content-section chapter-section"
          :class="{ active: activeKey === `chapter-${index}` }"
        >
          <div class="section-head">
            <el-input
              v-model="chapter.title"
              class="chapter-title-input"
              placeholder="章节标题"
              @input="markDirty"
              @focus="activeKey = `chapter-${index}`"
            />
            <el-button
              text
              type="danger"
              class="btn-del-chapter"
              @click="removeChapter(index)"
            >
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </div>

          <!-- 结构图 / 流程图：默认渲染图 + 代码模式 + AI 改图 -->
          <template v-if="isChartChapter(chapter)">
            <div @click="activeKey = `chapter-${index}`">
              <ChartSection
                v-model="chapter.content"
                :chart-type="chapter.type"
                :prd-id="id"
                :chapter-index="index"
                :title="chapter.title"
                @change="markDirty"
              />
            </div>
          </template>
          <template v-else>
            <RichTextEditor
              v-model="chapter.content"
              placeholder="编辑本章节内容，支持加粗、列表、标题…"
              min-height="180px"
              @update:model-value="markDirty"
              @focus="activeKey = `chapter-${index}`"
            />
            <div v-if="hasMermaid(chapter.content)" class="chart-preview">
              <div class="chart-preview-label">图表预览</div>
              <MarkdownContent :content="chapter.content" />
            </div>
          </template>
        </section>

        <div class="content-bottom-add">
          <el-button plain @click="openAddChapter">
            <el-icon><Plus /></el-icon>
            在底部新增章节
          </el-button>
        </div>

        <!-- 对比模式 -->
        <aside v-if="route.query.compare" class="compare-block">
          <div class="compare-heading">
            <span>原始版本对比</span>
            <span v-if="compareLoading">加载中…</span>
          </div>
          <template v-if="compare">
            <h3 class="compare-title">{{ compare.title }}</h3>
            <p class="compare-summary">{{ compare.summary }}</p>
            <article
              v-for="(ch, i) in compare.chapters"
              :key="i"
              class="compare-chapter"
            >
              <h4>{{ ch.title }}</h4>
              <MarkdownContent :content="ch.content" />
            </article>
          </template>
        </aside>
      </main>

      <!-- 审查问题侧栏 -->
      <aside v-if="fromReviewId && reviewIssues.length" class="review-side" :class="{ collapsed: !reviewPanelOpen }">
        <div class="review-side-head">
          <div>
            <div class="review-side-title">{{ reviewSideTitle }}</div>
            <div class="review-side-meta" v-if="reviewScore != null">
              评分 <strong>{{ reviewScore }}</strong> · {{ reviewIssues.length }} 条
              <span v-if="reviewFilteredFixedCount"> · 已处理 {{ reviewFilteredFixedCount }}/{{ reviewTotalIssueCount }} 条</span>
            </div>
          </div>
          <el-button text size="small" @click="reviewPanelOpen = !reviewPanelOpen">
            {{ reviewPanelOpen ? '收起' : '展开' }}
          </el-button>
        </div>
        <template v-if="reviewPanelOpen">
          <p v-if="reviewSummary" class="review-side-summary">{{ reviewSummary }}</p>
          <p v-if="reviewFilteredFixedCount" class="review-side-note">
            本页保留未在本次修复中选中的旧报告问题，是否已解决建议重新审查确认。
          </p>
          <div class="review-side-actions">
            <el-button
              size="small"
              type="primary"
              :loading="reviewFixTarget === 'batch'"
              :disabled="reviewCriticalMajorCount === 0 || (reviewFixing && reviewFixTarget !== 'batch')"
              @click="aiFixReview()"
            >
              <el-icon><MagicStick /></el-icon>
              AI 修复严重项 {{ reviewCriticalMajorCount }}
            </el-button>
          </div>
          <div class="review-issue-list">
            <div
              v-for="(issue, idx) in reviewIssues"
              :key="idx"
              role="button"
              tabindex="0"
              class="review-issue-item"
              :class="{ highlight: highlightIssueDisplayIndex === idx }"
              @click="jumpToIssue(idx)"
              @keydown.enter.prevent="jumpToIssue(idx)"
            >
              <div class="ri-head">
                <span
                  class="ri-sev"
                  :style="{ color: severityMeta(issue.severity).color, background: severityMeta(issue.severity).bg }"
                >{{ severityMeta(issue.severity).label }}</span>
                <span class="ri-loc">{{ issue.location }}</span>
              </div>
              <p class="ri-desc">{{ issue.description }}</p>
              <p class="ri-sug"><strong>建议：</strong>{{ issue.suggestion }}</p>
              <div class="ri-actions">
                <el-button
                  size="small"
                  text
                  type="warning"
                  :loading="reviewInlineFixing === idx"
                  :disabled="(reviewFixing || reviewInlineFixing !== null) && reviewInlineFixing !== idx"
                  @click.stop="aiFixInline(idx)"
                >
                  内联修复此条
                </el-button>
                <el-button
                  size="small"
                  text
                  :loading="reviewFixTarget === reviewIssueOriginalIndex(issue, idx)"
                  :disabled="(reviewFixing || reviewInlineFixing !== null) && reviewFixTarget !== reviewIssueOriginalIndex(issue, idx)"
                  @click.stop="aiFixReview([reviewIssueOriginalIndex(issue, idx)])"
                >
                  AI 修复此条
                </el-button>
              </div>
            </div>
          </div>
        </template>
      </aside>
    </div>

    <!-- 新增章节对话框 -->
    <el-dialog
      v-model="addDialogVisible"
      title="新增章节"
      width="420px"
      align-center
      @opened="() => {}"
    >
      <el-form @submit.prevent="confirmAddChapter">
        <el-form-item label="章节标题" required>
          <el-input
            v-model="newChapterTitle"
            placeholder="例如：6. 配置项"
            maxlength="80"
            show-word-limit
            autofocus
            @keyup.enter="confirmAddChapter"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAddChapter">确定新增</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.prd-page {
  height: 100%;
  min-height: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  background: #f2f1ed;
  box-sizing: border-box;
  overflow: hidden;
}

/* ── Toolbar ─────────────────────────────────────────── */
.prd-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 20px;
  background: #f7f7f4;
  border-bottom: 1px solid rgba(38, 37, 30, 0.1);
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.back-btn { color: rgba(38, 37, 30, 0.55) !important; font-size: 13px; }
.back-btn:hover { color: #cf2d56 !important; }
.toolbar-divider {
  width: 1px;
  height: 20px;
  background: rgba(38, 37, 30, 0.12);
  flex-shrink: 0;
}
.title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.doc-title-input {
  max-width: 420px;
  min-width: 160px;
}
.doc-title-input :deep(.el-input__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding-left: 0;
}
.doc-title-input :deep(.el-input__inner) {
  font-size: 17px;
  font-weight: 500;
  color: #26251e;
  height: 32px;
}
.doc-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: rgba(38, 37, 30, 0.4);
  white-space: nowrap;
}
.tag-custom,
.tag-dirty {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
}
.tag-custom {
  background: rgba(31, 138, 101, 0.12);
  color: #1f8a65;
}
.tag-dirty {
  background: rgba(192, 133, 50, 0.15);
  color: #c08532;
}
.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex-shrink: 0;
}

/* ── Workspace ───────────────────────────────────────── */
.prd-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

/* ── Outline ─────────────────────────────────────────── */
.outline-panel {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #f7f7f4;
  border-right: 1px solid rgba(38, 37, 30, 0.1);
}
.outline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px 10px;
}
.outline-title {
  font-size: 13px;
  font-weight: 600;
  color: #26251e;
  letter-spacing: 0.02em;
}
.outline-count {
  font-size: 12px;
  color: rgba(38, 37, 30, 0.4);
}
.outline-nav {
  flex: 1;
  overflow-y: auto;
  padding: 4px 10px 12px;
}
.outline-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  text-align: left;
  padding: 8px 10px;
  margin-bottom: 2px;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(38, 37, 30, 0.7);
  font-size: 13px;
  line-height: 1.4;
  transition: background .12s, color .12s;
  font-family: inherit;
}
.outline-item:hover {
  background: #e6e5e0;
  color: #26251e;
}
.outline-item.active {
  background: #26251e;
  color: #f2f1ed;
}
.outline-item.active .outline-dot {
  background: #f54e00;
}
.outline-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(38, 37, 30, 0.25);
  flex-shrink: 0;
}
.outline-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.outline-item.level-2 { padding-left: 22px; font-size: 12.5px; }
.outline-item.level-3 { padding-left: 34px; font-size: 12px; color: rgba(38, 37, 30, 0.55); }
.outline-item.level-3.active { color: #f2f1ed; }

.outline-footer {
  padding: 12px 14px 16px;
  border-top: 1px solid rgba(38, 37, 30, 0.08);
}
.btn-add-chapter {
  width: 100%;
  border-radius: 8px !important;
  border-style: dashed !important;
  color: rgba(38, 37, 30, 0.65) !important;
  background: transparent !important;
}
.btn-add-chapter:hover {
  color: #f54e00 !important;
  border-color: #f54e00 !important;
  background: rgba(245, 78, 0, 0.04) !important;
}

/* ── Content ─────────────────────────────────────────── */
.content-panel {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 28px 40px 64px;
  background: #f2f1ed;
  scroll-behavior: smooth;
}
.doc-desc-block {
  margin-bottom: 20px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid rgba(38, 37, 30, 0.08);
  border-radius: 10px;
}
.field-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: rgba(38, 37, 30, 0.5);
  margin-bottom: 8px;
}

.content-section {
  background: #fff;
  border: 1px solid rgba(38, 37, 30, 0.08);
  border-radius: 10px;
  padding: 20px 22px;
  margin-bottom: 16px;
  transition: box-shadow .2s, border-color .2s;
}
.content-section.active {
  border-color: rgba(38, 37, 30, 0.18);
  box-shadow: 0 0 0 1px rgba(38, 37, 30, 0.04);
}
.content-section.section-flash {
  animation: flash-border 0.9s ease;
}
.content-section :deep(.issue-focus-flash) {
  animation: issue-focus-flash 1.1s ease;
  border-radius: 6px;
}
@keyframes flash-border {
  0%   { box-shadow: 0 0 0 2px rgba(245, 78, 0, 0.45); }
  100% { box-shadow: 0 0 0 0 transparent; }
}
@keyframes issue-focus-flash {
  0%   { background: rgba(245, 78, 0, 0.18); box-shadow: 0 0 0 4px rgba(245, 78, 0, 0.12); }
  100% { background: transparent; box-shadow: 0 0 0 0 transparent; }
}
.content-section :deep(.inline-fix-flash) {
  animation: inline-fix-flash 2s ease;
  border-radius: 6px;
}
@keyframes inline-fix-flash {
  0%   { background: rgba(11, 182, 199, 0.22); box-shadow: 0 0 0 3px rgba(11, 182, 199, 0.15); }
  50%  { background: rgba(11, 182, 199, 0.12); box-shadow: 0 0 0 2px rgba(11, 182, 199, 0.08); }
  100% { background: transparent; box-shadow: 0 0 0 0 transparent; }
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #26251e;
  letter-spacing: -0.01em;
}
.chapter-title-input {
  flex: 1;
}
.chapter-title-input :deep(.el-input__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding-left: 0;
}
.chapter-title-input :deep(.el-input__inner) {
  font-size: 16px;
  font-weight: 600;
  color: #26251e;
  height: 32px;
}
.btn-del-chapter {
  flex-shrink: 0;
  opacity: 0.55;
}
.btn-del-chapter:hover { opacity: 1; }

.chart-preview {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed rgba(38, 37, 30, 0.12);
}
.chart-preview-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: rgba(38, 37, 30, 0.5);
  margin-bottom: 8px;
  letter-spacing: 0.02em;
}
.chart-type-tag {
  font-weight: 500;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(31, 138, 101, 0.1);
  color: #1f8a65;
}

.content-bottom-add {
  display: flex;
  justify-content: center;
  padding: 12px 0 8px;
}

/* ── Review side panel ──────────────────────────────── */
.review-side {
  width: 320px;
  flex-shrink: 0;
  border-left: 1px solid rgba(38, 37, 30, 0.1);
  background: #fbfaf7;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.review-side.collapsed {
  width: 140px;
}
.review-side-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 14px 14px 10px;
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
}
.review-side-title {
  font-size: 13px;
  font-weight: 600;
  color: #26251e;
}
.review-side-meta {
  font-size: 12px;
  color: rgba(38, 37, 30, 0.5);
  margin-top: 2px;
}
.review-side-meta strong { color: #c08532; font-size: 14px; }
.review-side-summary {
  margin: 0;
  padding: 10px 14px;
  font-size: 12px;
  color: rgba(38, 37, 30, 0.65);
  line-height: 1.5;
  border-bottom: 1px solid rgba(38, 37, 30, 0.06);
}
.review-side-note {
  margin: 0;
  padding: 9px 14px;
  font-size: 12px;
  color: #8a5d1f;
  line-height: 1.5;
  background: rgba(192, 133, 50, 0.08);
  border-bottom: 1px solid rgba(38, 37, 30, 0.06);
}
.review-side-actions {
  padding: 10px 14px;
  border-bottom: 1px solid rgba(38, 37, 30, 0.06);
}
.review-side-actions :deep(.el-button) {
  width: 100%;
}
.review-issue-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.review-issue-item {
  border: 1px solid rgba(38, 37, 30, 0.08);
  background: #fff;
  border-radius: 8px;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: border-color .15s, box-shadow .15s;
  outline: none;
}
.review-issue-item:hover {
  border-color: rgba(245, 78, 0, 0.35);
}
.review-issue-item.highlight {
  border-color: #f54e00;
  box-shadow: 0 0 0 2px rgba(245, 78, 0, 0.12);
}
.ri-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.ri-sev {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  font-weight: 500;
  flex-shrink: 0;
}
.ri-loc {
  font-size: 12px;
  color: #f54e00;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ri-desc {
  margin: 0 0 6px;
  font-size: 12.5px;
  color: #26251e;
  line-height: 1.5;
}
.ri-sug {
  margin: 0;
  font-size: 12px;
  color: #1f8a65;
  line-height: 1.45;
  background: rgba(31, 138, 101, 0.06);
  padding: 6px 8px;
  border-radius: 6px;
}
.ri-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}

/* ── Compare ─────────────────────────────────────────── */
.compare-block {
  margin-top: 28px;
  padding: 20px;
  background: #fbfcfe;
  border: 1px dashed rgba(38, 37, 30, 0.15);
  border-radius: 10px;
}
.compare-heading {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.55);
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
  padding-bottom: 10px;
  margin-bottom: 12px;
}
.compare-title {
  font-size: 16px;
  font-weight: 500;
  margin: 0 0 8px;
  color: #26251e;
}
.compare-summary {
  color: rgba(38, 37, 30, 0.7);
  line-height: 1.6;
  margin: 0 0 12px;
}
.compare-chapter {
  border-top: 1px solid rgba(38, 37, 30, 0.08);
  padding: 12px 0;
}
.compare-chapter h4 {
  margin: 0 0 6px;
  font-size: 14px;
  color: #26251e;
}
.compare-content {
  margin: 0;
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.7);
  line-height: 1.7;
}

/* ── Responsive ──────────────────────────────────────── */
@media (max-width: 960px) {
  .prd-workspace { flex-direction: column; }
  .outline-panel {
    width: 100%;
    max-height: 200px;
    border-right: none;
    border-bottom: 1px solid rgba(38, 37, 30, 0.1);
  }
  .outline-nav { display: flex; flex-wrap: nowrap; overflow-x: auto; gap: 4px; padding: 0 10px 10px; }
  .outline-item { width: auto; white-space: nowrap; flex-shrink: 0; }
  .outline-item.level-2,
  .outline-item.level-3 { padding-left: 10px; }
  .outline-footer { display: none; }
  .content-panel { padding: 16px 16px 48px; }
  .prd-toolbar { flex-wrap: wrap; }
}
</style>
