<script setup lang="ts">
/**
 * 图表章节交互区
 * - 默认：渲染图表（PlantUML 走后端 / Mermaid 浏览器端）+ 下方说明文字
 * - 代码模式：可编辑源码 + 重新渲染
 * - AI 修改：自然语言修订图表
 */
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import {
  detectChartLang,
  extractChartCode,
  extractChartCaption,
  replaceChartInContent,
  renderChartImageUrl,
} from '@/utils/chart'
import client from '@/api/client'

const props = defineProps<{
  /** 章节完整 content（含图表 + 说明） */
  modelValue: string
  /** 章节类型 structure / flow / 其他 */
  chartType?: string
  /** PRD 文档 id，用于 AI 改图 */
  prdId?: number
  /** 章节下标 */
  chapterIndex?: number
  title?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: []
}>()

const mode = ref<'preview' | 'code'>('preview')
const code = ref('')
const caption = ref('')
const draftCode = ref('')
const containerRef = ref<HTMLElement | null>(null)
const plantumlUrl = ref<string | null>(null)
const rendering = ref(false)
const errorMsg = ref('')
const aiDialog = ref(false)
const aiInstruction = ref('')
const aiLoading = ref(false)
let renderSeq = 0

const chartLabel = computed(() => {
  if (props.chartType === 'structure') return '页面结构图'
  if (props.chartType === 'flow') return '流程图'
  return props.title || '图表'
})

function syncFromModel() {
  const content = props.modelValue || ''
  code.value = extractChartCode(content) || ''
  caption.value = extractChartCaption(content)
  draftCode.value = code.value
}

function emitContent(chartCode: string) {
  const next = replaceChartInContent(props.modelValue || '', chartCode)
  emit('update:modelValue', next)
  emit('change')
}

async function renderChart(source?: string) {
  const src = (source ?? code.value ?? '').trim()
  errorMsg.value = ''
  plantumlUrl.value = null
  if (!src) {
    errorMsg.value = '暂无图表源码'
    return
  }
  if (!containerRef.value) return
  rendering.value = true
  const seq = ++renderSeq

  const lang = detectChartLang(src)
  if (lang === 'plantuml') {
    const url = await renderChartImageUrl(src, lang)
    if (seq !== renderSeq) return
    if (url) {
      plantumlUrl.value = url
    } else {
      errorMsg.value = '图表渲染失败，请检查 PlantUML 语法'
    }
    rendering.value = false
    return
  }

  try {
    containerRef.value.innerHTML = ''
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'loose',
      suppressErrorRendering: true,
      theme: 'base',
      themeVariables: {
        primaryColor: '#e6e5e0',
        primaryTextColor: '#26251e',
        primaryBorderColor: 'rgba(38,37,30,0.25)',
        lineColor: 'rgba(38,37,30,0.45)',
        secondaryColor: '#f7f7f4',
        tertiaryColor: '#f2f1ed',
        fontFamily: 'system-ui, -apple-system, "Segoe UI", sans-serif',
        fontSize: '14px',
      },
      flowchart: { curve: 'basis', htmlLabels: true, padding: 12 },
    } as any)
    const id = `chart-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const { svg, bindFunctions } = await mermaid.render(id, src)
    if (seq !== renderSeq) return
    await nextTick()
    if (containerRef.value) {
      containerRef.value.innerHTML = svg
      bindFunctions?.(containerRef.value)
    }
  } catch (e: any) {
    if (seq !== renderSeq) return
    console.warn('chart render failed', e)
    errorMsg.value = '图表渲染失败，请检查 Mermaid 语法'
    if (containerRef.value) containerRef.value.innerHTML = ''
  } finally {
    if (seq === renderSeq) rendering.value = false
  }
}

function toggleCodeMode() {
  if (mode.value === 'preview') {
    draftCode.value = code.value
    mode.value = 'code'
  } else {
    mode.value = 'preview'
    nextTick(() => renderChart())
  }
}

async function applyCodeAndRender() {
  const next = draftCode.value.trim()
  if (!next) {
    ElMessage.warning('源码不能为空')
    return
  }
  code.value = next
  emitContent(next)
  mode.value = 'preview'
  await nextTick()
  await renderChart(next)
  ElMessage.success('已重新渲染')
}

function openAiDialog() {
  aiInstruction.value = ''
  aiDialog.value = true
}

async function submitAiRevise() {
  const instruction = aiInstruction.value.trim()
  if (!instruction) {
    ElMessage.warning('请描述希望如何修改图表')
    return
  }
  if (props.prdId == null || props.chapterIndex == null) {
    ElMessage.warning('缺少文档信息，无法调用 AI 修改')
    return
  }
  aiLoading.value = true
  try {
    const res = await client.post(`/prd/${props.prdId}/chart-revise`, {
      chapterIndex: props.chapterIndex,
      instruction,
      currentCode: code.value,
    })
    const newCode = res.data?.data?.code || res.data?.data?.mermaid
    const newContent = res.data?.data?.content
    if (newContent) {
      emit('update:modelValue', newContent)
      emit('change')
      syncFromModel()
    } else if (newCode) {
      code.value = newCode
      draftCode.value = newCode
      emitContent(newCode)
    } else {
      throw new Error('AI 未返回有效图表')
    }
    aiDialog.value = false
    mode.value = 'preview'
    await nextTick()
    await renderChart()
    ElMessage.success('图表已更新（已自动保存到服务端）')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || 'AI 修改失败')
  } finally {
    aiLoading.value = false
  }
}

/** 供父组件导出时获取当前 SVG */
function getSvgHtml(): string | null {
  return containerRef.value?.innerHTML || null
}

onMounted(() => {
  syncFromModel()
  nextTick(() => renderChart())
})

watch(() => props.modelValue, (v, old) => {
  if (v === old) return
  // 外部更新时同步（避免编辑框自己触发的循环：比较图表源码）
  const nextCode = extractChartCode(v || '') || ''
  if (nextCode !== code.value) {
    syncFromModel()
    if (mode.value === 'preview') nextTick(() => renderChart())
  } else {
    caption.value = extractChartCaption(v || '')
  }
})

defineExpose({ getSvgHtml, getChartCode: () => code.value, renderChart })
</script>

<template>
  <div class="chart-section" :data-chart-index="chapterIndex">
    <div class="chart-toolbar">
      <div class="chart-title-row">
        <span class="chart-badge">{{ chartLabel }}</span>
        <span class="chart-mode-tag">{{ mode === 'preview' ? '预览' : '代码' }}</span>
      </div>
      <div class="chart-actions">
        <el-button size="small" @click="toggleCodeMode">
          {{ mode === 'preview' ? '切换到代码模式' : '返回预览' }}
        </el-button>
        <el-button size="small" type="primary" plain @click="openAiDialog">
          用 AI 修改图表
        </el-button>
      </div>
    </div>

    <!-- 预览模式 -->
    <div v-show="mode === 'preview'" class="chart-preview-body">
      <div v-if="rendering" class="chart-loading">图表渲染中…</div>
      <div v-if="errorMsg" class="chart-error">
        <p class="err-title">图表暂时无法显示</p>
        <p class="err-desc">{{ errorMsg }}</p>
        <el-button size="small" @click="mode = 'code'">去代码模式修复</el-button>
      </div>
      <div
        v-if="plantumlUrl"
        class="chart-img"
      >
        <img :src="plantumlUrl" alt="图表" />
      </div>
      <div
        ref="containerRef"
        class="chart-svg"
        :class="{ hidden: !!errorMsg || !!plantumlUrl }"
      />
      <div v-if="caption" class="chart-caption">
        <div class="caption-label">说明</div>
        <div class="caption-text">{{ caption }}</div>
      </div>
    </div>

    <!-- 代码模式 -->
    <div v-show="mode === 'code'" class="chart-code-body">
      <el-input
        v-model="draftCode"
        type="textarea"
        :rows="14"
        class="code-input"
        placeholder="在此编辑图表源码（PlantUML / Mermaid）…"
      />
      <div class="code-actions">
        <el-button type="primary" @click="applyCodeAndRender">重新渲染</el-button>
        <el-button @click="draftCode = code; mode = 'preview'; nextTick(() => renderChart())">取消</el-button>
      </div>
    </div>

    <!-- AI 修改对话框 -->
    <el-dialog
      v-model="aiDialog"
      title="用 AI 修改图表"
      width="480px"
      align-center
      destroy-on-close
    >
      <p class="ai-hint">用自然语言描述修改需求，例如：「把登录模块移到顶部」「增加异常分支」</p>
      <el-input
        v-model="aiInstruction"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="描述你希望图表如何变化…"
      />
      <template #footer>
        <el-button @click="aiDialog = false">取消</el-button>
        <el-button type="primary" :loading="aiLoading" @click="submitAiRevise">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chart-section {
  margin-top: 14px;
  border: 1px solid rgba(38, 37, 30, 0.1);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.chart-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  background: #f7f7f4;
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
}
.chart-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chart-badge {
  font-size: 12px;
  font-weight: 600;
  color: #1f8a65;
  background: rgba(31, 138, 101, 0.1);
  padding: 2px 10px;
  border-radius: 999px;
}
.chart-mode-tag {
  font-size: 11px;
  color: rgba(38, 37, 30, 0.45);
}
.chart-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chart-preview-body { padding: 8px 12px 16px; }
.chart-loading {
  padding: 28px;
  text-align: center;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.45);
}
.chart-error {
  padding: 16px;
  background: rgba(207, 45, 86, 0.04);
  border-radius: 8px;
  margin: 8px;
}
.err-title { margin: 0 0 4px; font-size: 13px; font-weight: 600; color: #cf2d56; }
.err-desc { margin: 0 0 10px; font-size: 12px; color: rgba(38, 37, 30, 0.65); word-break: break-word; }
.chart-svg {
  padding: 12px;
  overflow-x: auto;
  text-align: center;
  min-height: 80px;
}
.chart-svg.hidden { display: none; }
.chart-svg :deep(svg) {
  max-width: 100%;
  height: auto;
}
.chart-img {
  padding: 12px;
  overflow-x: auto;
  text-align: center;
}
.chart-img img {
  max-width: 100%;
  height: auto;
}
.chart-caption {
  margin: 8px 8px 0;
  padding: 12px 14px;
  background: #f7f7f4;
  border-radius: 8px;
}
.caption-label {
  font-size: 11px;
  font-weight: 600;
  color: rgba(38, 37, 30, 0.45);
  margin-bottom: 6px;
  letter-spacing: 0.04em;
}
.caption-text {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(38, 37, 30, 0.8);
  white-space: pre-wrap;
}

.chart-code-body { padding: 12px 14px 16px; }
.code-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.5;
}
.code-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.ai-hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.65);
  line-height: 1.5;
}
</style>
