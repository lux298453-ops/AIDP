<script setup lang="ts">
/**
 * 轻量 Markdown/纯文本展示：
 * - 拆出 ```plantuml / ```mermaid 代码块 → MermaidBlock
 * - 其余按段落 / 列表 / 表格展示
 * 用于 PRD 章节只读对比与增强结果中的图表渲染
 */
import { computed } from 'vue'
import MermaidBlock from './MermaidBlock.vue'
import { plainToHtml } from './markdown'

const props = defineProps<{
  content: string
  /** 是否作为可编辑纯文本旁的只读预览（默认 true 渲染图表） */
  renderCharts?: boolean
}>()

type Block =
  | { kind: 'chart'; code: string }
  | { kind: 'code'; lang: string; code: string }
  | { kind: 'html'; html: string }

const blocks = computed<Block[]>(() => parseContent(props.content || ''))

function parseContent(raw: string): Block[] {
  if (!raw) return []
  const text = raw.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
  const result: Block[] = []
  // 匹配 ```lang\n...\n```
  const fenceRe = /```([a-zA-Z0-9_-]*)[ \t]*\n([\s\S]*?)```/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = fenceRe.exec(text)) !== null) {
    if (m.index > last) {
      result.push({ kind: 'html', html: plainToHtml(text.slice(last, m.index)) })
    }
    const lang = (m[1] || '').toLowerCase()
    const code = m[2].trim()
    if (lang === 'plantuml' || lang === 'mermaid' || (!lang && /^(flowchart|graph|sequencediagram|@startuml)\b/i.test(code))) {
      result.push({ kind: 'chart', code })
    } else {
      result.push({ kind: 'code', lang, code })
    }
    last = m.index + m[0].length
  }
  if (last < text.length) {
    // 无围栏但含裸图表（flowchart / @startuml）的兜底
    const rest = text.slice(last)
    const naked = splitNakedChart(rest)
    result.push(...naked)
  }

  // 若整段没有 fence，再尝试裸图表
  if (result.length === 0) {
    return splitNakedChart(text)
  }
  return result
}

function splitNakedChart(text: string): Block[] {
  const lines = text.split('\n')
  const out: Block[] = []
  let buf: string[] = []
  let chart: string[] | null = null

  const flushBuf = () => {
    if (buf.length) {
      out.push({ kind: 'html', html: plainToHtml(buf.join('\n')) })
      buf = []
    }
  }
  const flushChart = () => {
    if (chart && chart.length) {
      out.push({ kind: 'chart', code: chart.join('\n').trim() })
      chart = null
    }
  }

  for (const line of lines) {
    const t = line.trim()
    if (!chart && /^(flowchart|graph|sequenceDiagram)\b/i.test(t)) {
      flushBuf()
      chart = [line]
      continue
    }
    if (!chart && /^@startuml\b/i.test(t)) {
      flushBuf()
      chart = [line]
      continue
    }
    if (chart) {
      if (
        t === '' ||
        /@startuml|@enduml|(--|==>|-->|\[|\]|\(|\)|subgraph|\bend\b|participant|Note|style |classDef)/i.test(t) ||
        /^[A-Za-z][\w]*([\[{(].*)?$/.test(t)
      ) {
        chart.push(line)
        if (/^@enduml\b/i.test(t)) {
          flushChart()
          continue
        }
        continue
      }
      flushChart()
      buf.push(line)
      continue
    }
    buf.push(line)
  }
  flushChart()
  flushBuf()
  return out
}
</script>

<template>
  <div class="md-content">
    <template v-for="(block, i) in blocks" :key="i">
      <MermaidBlock
        v-if="block.kind === 'chart' && renderCharts !== false"
        :code="block.code"
      />
      <pre v-else-if="block.kind === 'chart'" class="code-fallback">{{ block.code }}</pre>
      <pre v-else-if="block.kind === 'code'" class="code-block"><code>{{ block.code }}</code></pre>
      <div v-else class="md-html" v-html="block.html" />
    </template>
  </div>
</template>

<style scoped>
.md-content {
  font-size: 14px;
  line-height: 1.75;
  color: #26251e;
  word-break: break-word;
}
.md-html :deep(p) { margin: 0 0 8px; }
.md-html :deep(p.blank) { margin: 0 0 4px; }
.md-html :deep(h3),
.md-html :deep(h4),
.md-html :deep(h5) {
  margin: 12px 0 8px;
  font-weight: 600;
  color: #26251e;
}
.md-html :deep(ul),
.md-html :deep(ol) {
  margin: 6px 0 10px;
  padding-left: 22px;
}
.md-html :deep(li) { margin: 3px 0; }
.md-html :deep(blockquote) {
  margin: 8px 0;
  padding: 8px 12px;
  border-left: 3px solid rgba(192, 133, 50, 0.5);
  background: rgba(192, 133, 50, 0.06);
  color: rgba(38, 37, 30, 0.75);
  border-radius: 0 6px 6px 0;
}
.md-html :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  background: #f2f1ed;
  padding: 1px 5px;
  border-radius: 4px;
}
.md-html :deep(.md-table-wrap) {
  overflow-x: auto;
  margin: 8px 0 12px;
}
.md-html :deep(.md-table) {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  background: #fff;
  border: 1px solid rgba(38, 37, 30, 0.12);
  font-size: 13px;
}
.md-html :deep(.md-table th),
.md-html :deep(.md-table td) {
  border: 1px solid rgba(38, 37, 30, 0.12);
  padding: 8px 10px;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
}
.md-html :deep(.md-table th) {
  background: #f7f7f4;
  font-weight: 600;
}
.code-block,
.code-fallback {
  margin: 8px 0;
  padding: 12px;
  background: #f2f1ed;
  border-radius: 8px;
  font-size: 12.5px;
  overflow-x: auto;
  white-space: pre-wrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
