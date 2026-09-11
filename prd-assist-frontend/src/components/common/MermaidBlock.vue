<script setup lang="ts">
import { ref, watch, onMounted, nextTick } from 'vue'
import { detectChartLang, renderChartSvgHtml } from '@/utils/chart'

const props = defineProps<{
  code: string
  title?: string
}>()

const containerRef = ref<HTMLElement | null>(null)
const errorMsg = ref('')
const rendering = ref(false)
let renderSeq = 0

async function render() {
  const code = (props.code || '').trim()
  errorMsg.value = ''
  if (containerRef.value) containerRef.value.innerHTML = ''
  if (!code) {
    errorMsg.value = '图表源码为空'
    return
  }
  if (!containerRef.value) return

  const lang = detectChartLang(code)
  if (lang === 'plantuml') {
    rendering.value = true
    const seq = ++renderSeq
    const svg = await renderChartSvgHtml(code, lang)
    if (seq !== renderSeq) return
    if (svg) {
      await nextTick()
      if (containerRef.value) {
        containerRef.value.innerHTML = svg
      }
    } else {
      errorMsg.value = '图表渲染失败'
    }
    rendering.value = false
    return
  }

  rendering.value = true
  const seq = ++renderSeq
  try {
    containerRef.value.innerHTML = ''
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      suppressErrorRendering: true,
      theme: 'base',
      themeVariables: {
        primaryColor: '#e8f0fe',
        primaryTextColor: '#1a1a1a',
        primaryBorderColor: 'rgba(91,124,250,0.45)',
        lineColor: 'rgba(38,37,30,0.60)',
        secondaryColor: '#f7f7f4',
        tertiaryColor: '#ffffff',
        fontFamily: 'Microsoft YaHei, PingFang SC, Noto Sans SC, Arial, sans-serif',
        fontSize: '18px',
      },
      flowchart: { curve: 'linear', htmlLabels: false, padding: 20, nodeSpacing: 72, rankSpacing: 88 },
    } as any)

    const id = `mmd-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const { svg, bindFunctions } = await mermaid.render(id, code)
    if (seq !== renderSeq) return
    await nextTick()
    if (containerRef.value) {
      containerRef.value.innerHTML = svg
      bindFunctions?.(containerRef.value)
    }
  } catch (e: any) {
    if (seq !== renderSeq) return
    console.warn('chart render failed', e)
    errorMsg.value = '图表渲染失败'
    if (containerRef.value) containerRef.value.innerHTML = ''
  } finally {
    if (seq === renderSeq) rendering.value = false
  }
}

onMounted(render)
watch(() => props.code, () => { render() })
</script>

<template>
  <div v-if="!errorMsg" class="mermaid-block">
    <div class="mermaid-toolbar">
      <span class="mermaid-label">{{ title || '图表' }}</span>
    </div>

    <div v-if="rendering" class="mermaid-loading">图表渲染中...</div>
    <div v-else ref="containerRef" class="mermaid-svg" />
  </div>
</template>

<style scoped>
.mermaid-block {
  margin: 12px 0 16px;
  border: 1px solid rgba(38, 37, 30, 0.1);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.mermaid-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #f7f7f4;
  border-bottom: 1px solid rgba(38, 37, 30, 0.06);
}
.mermaid-label {
  font-size: 12px;
  font-weight: 600;
  color: rgba(38, 37, 30, 0.65);
  letter-spacing: 0.02em;
}
.mermaid-loading {
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.45);
}
.mermaid-svg {
  padding: 16px;
  overflow-x: auto;
  text-align: center;
}
.mermaid-svg :deep(svg) {
  max-width: 100%;
  height: auto;
}
</style>
