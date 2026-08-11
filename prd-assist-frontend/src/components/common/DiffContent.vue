<script setup lang="ts">
/**
 * 章节级 Diff 对比渲染：
 * - 按空行 + 代码围栏分块 → 块级 LCS 对齐（same / add / del / mod）
 * - 简单段落块（无列表/表格/围栏）做行级 diff，行内高亮
 * - 复杂块（列表/表格/图表）整体红/绿高亮
 * - 未变更块默认折叠，可展开查看原文
 */
import { computed, ref } from 'vue'
import { plainToHtml, inlineFormat, isTableLine } from './markdown'

const props = defineProps<{
  oldContent: string
  newContent: string
}>()

type Op =
  | { type: 'same'; text: string }
  | { type: 'del'; text: string }
  | { type: 'add'; text: string }

const showSame = ref(false)

function normalizeKey(text: string): string {
  return text.replace(/\s+/g, ' ').trim().toLowerCase()
}

/** 按空行 + 围栏拆块（保留围栏为整体块） */
function splitBlocks(raw: string): string[] {
  const text = raw.replace(/\r\n/g, '\n')
  const blocks: string[] = []
  let plain: string[] = []
  const flushPlain = () => {
    if (plain.length) {
      blocks.push(plain.join('\n'))
      plain = []
    }
  }
  const fenceRe = /```[^\n]*\n[\s\S]*?```/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = fenceRe.exec(text)) !== null) {
    if (m.index > last) {
      plain.push(text.slice(last, m.index))
    }
    flushPlain()
    blocks.push(m[0])
    last = m.index + m[0].length
  }
  if (last < text.length) plain.push(text.slice(last))
  flushPlain()
  return blocks
    .map(b => b.trim())
    .filter(Boolean)
}

/** LCS 对齐，返回操作序列（del 后 add 表示替换） */
function diffOps(oldArr: string[], newArr: string[]): Op[] {
  const n = oldArr.length
  const m = newArr.length
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = oldArr[i] === newArr[j]
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const ops: Op[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (oldArr[i] === newArr[j]) {
      ops.push({ type: 'same', text: oldArr[i] })
      i++; j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      ops.push({ type: 'del', text: oldArr[i] })
      i++
    } else {
      ops.push({ type: 'add', text: newArr[j] })
      j++
    }
  }
  while (i < n) { ops.push({ type: 'del', text: oldArr[i] }); i++ }
  while (j < m) { ops.push({ type: 'add', text: newArr[j] }); j++ }
  return ops
}

/** 简单块判定：无列表/表格/围栏/图表，每行仅普通文本/标题/引用 */
function isSimpleBlock(text: string): boolean {
  const lines = text.split('\n').filter(l => l.trim() !== '')
  if (!lines.length) return false
  return lines.every(l => {
    const t = l.trim()
    if (isTableLine(t)) return false
    if (/^[-*•]\s+/.test(t) || /^\d+[.)、]\s+/.test(t)) return false
    if (/^```/.test(t)) return false
    if (/^(flowchart|graph|sequenceDiagram|@startuml)\b/i.test(t)) return false
    return true
  })
}

/** 行级渲染（仅简单块），返回 HTML */
function renderSimpleLines(ops: Op[]): string {
  return ops.map(op => {
    const t = op.text.replace(/^\s+/, '')
    if (op.type === 'same') {
      return renderSimpleLine(t, '')
    }
    if (op.type === 'del') {
      return renderSimpleLine(t, 'd-del')
    }
    return renderSimpleLine(t, 'd-add')
  }).join('')
}

function renderSimpleLine(line: string, cls: string): string {
  const wrap = (html: string, tag = 'p') => `<${tag} class="${cls}">${html}</${tag}>`
  const t = line.trim()
  if (/^#{1,3}\s+/.test(t)) {
    const level = t.match(/^(#{1,3})/)?.[1].length || 2
    const title = t.replace(/^#{1,3}\s+/, '')
    return wrap(inlineFormat(title), `h${level + 2}`)
  }
  if (t.startsWith('>')) {
    const html = `<span class="${cls}">${inlineFormat(t.replace(/^\s*>\s?/, ''))}</span>`
    return `<blockquote class="${cls}">${html}</blockquote>`
  }
  return wrap(inlineFormat(t), 'p')
}

interface RenderedBlock {
  key: string
  status: 'same' | 'add' | 'del' | 'mod'
  html: string
}

const blocks = computed<RenderedBlock[]>(() => {
  const oldBlocks = splitBlocks(props.oldContent)
  const newBlocks = splitBlocks(props.newContent)
  const ops = diffOps(
    oldBlocks.map(normalizeKey),
    newBlocks.map(normalizeKey),
  )
  const out: RenderedBlock[] = []

  // 将 del/add 归并为「变更 run」：dels 与 adds 同时存在时视为 mod
  let runDels: string[] = []
  let runAdds: string[] = []
  const flushRun = () => {
    if (!runDels.length && !runAdds.length) return
    if (runDels.length && !runAdds.length) {
      for (const t of runDels) out.push({ key: `del-${out.length}`, status: 'del', html: plainToHtml(t) })
    } else if (!runDels.length && runAdds.length) {
      for (const t of runAdds) out.push({ key: `add-${out.length}`, status: 'add', html: plainToHtml(t) })
    } else {
      // mod：简单块行级 diff，复杂块红绿整块
      if (isSimpleBlock(runDels.join('\n\n')) && isSimpleBlock(runAdds.join('\n\n'))) {
        const dels = runDels.flatMap(b => b.split('\n')).filter(l => l.trim() !== '')
        const adds = runAdds.flatMap(b => b.split('\n')).filter(l => l.trim() !== '')
        out.push({
          key: `mod-${out.length}`,
          status: 'mod',
          html: renderSimpleLines(diffOps(dels, adds)),
        })
      } else {
        for (const t of runDels) out.push({ key: `del-${out.length}`, status: 'del', html: plainToHtml(t) })
        for (const t of runAdds) out.push({ key: `add-${out.length}`, status: 'add', html: plainToHtml(t) })
      }
    }
    runDels = []
    runAdds = []
  }

  for (const op of ops) {
    if (op.type === 'same') {
      flushRun()
      out.push({ key: `same-${out.length}`, status: 'same', html: plainToHtml(op.text) })
    } else if (op.type === 'del') {
      runDels.push(op.text)
    } else {
      runAdds.push(op.text)
    }
  }
  flushRun()
  return out
})

const stats = computed(() => {
  const s = { addBlocks: 0, delBlocks: 0, modBlocks: 0, sameBlocks: 0, unchanged: false }
  for (const b of blocks.value) {
    if (b.status === 'add') s.addBlocks++
    else if (b.status === 'del') s.delBlocks++
    else if (b.status === 'mod') s.modBlocks++
    else s.sameBlocks++
  }
  s.unchanged = s.addBlocks === 0 && s.delBlocks === 0 && s.modBlocks === 0
  return s
})
</script>

<template>
  <div class="diff-content">
    <div v-if="stats.unchanged" class="diff-nochange">内容无变化</div>
    <template v-else>
      <div class="diff-summary">
        <span v-if="stats.addBlocks" class="ds ds-add">新增 {{ stats.addBlocks }} 段</span>
        <span v-if="stats.delBlocks" class="ds ds-del">删除 {{ stats.delBlocks }} 段</span>
        <span v-if="stats.modBlocks" class="ds ds-mod">修改 {{ stats.modBlocks }} 段</span>
        <span v-if="stats.sameBlocks" class="ds ds-same">
          <a href="javascript:;" @click.prevent="showSame = !showSame">
            {{ showSame ? '收起' : '展开' }}未变更 {{ stats.sameBlocks }} 段
          </a>
        </span>
      </div>
      <template v-for="b in blocks" :key="b.key">
        <div v-if="b.status === 'same' && showSame" class="md-html" v-html="b.html" />
        <div v-else-if="b.status === 'add'" class="d-block d-add-block md-html" v-html="b.html" />
        <div v-else-if="b.status === 'del'" class="d-block d-del-block md-html" v-html="b.html" />
        <div v-else class="d-block md-html" v-html="b.html" />
      </template>
    </template>
  </div>
</template>

<style scoped>
.diff-content {
  font-size: 14px;
  line-height: 1.75;
  color: #26251e;
}
.diff-nochange {
  color: rgba(38, 37, 30, 0.45);
  font-size: 13px;
  padding: 6px 0;
}
.diff-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 2px 0 10px;
}
.ds {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  background: #f2f1ed;
  color: rgba(38, 37, 30, 0.7);
}
.ds-add { background: rgba(64, 158, 87, 0.12); color: #2e7d43; }
.ds-del { background: rgba(192, 84, 68, 0.12); color: #b0443a; }
.ds-mod { background: rgba(192, 133, 50, 0.14); color: #a06a1d; }
.ds-same a { color: inherit; text-decoration: none; }
.ds-same a:hover { text-decoration: underline; }
.d-block { position: relative; }
.d-add-block {
  background: rgba(64, 158, 87, 0.08);
  box-shadow: inset 3px 0 0 rgba(64, 158, 87, 0.55);
  padding: 2px 8px;
  border-radius: 4px;
  margin: 2px 0;
}
.d-del-block {
  background: rgba(192, 84, 68, 0.06);
  box-shadow: inset 3px 0 0 rgba(192, 84, 68, 0.5);
  padding: 2px 8px;
  border-radius: 4px;
  margin: 2px 0;
}
.d-del-block :deep(*) {
  color: rgba(38, 37, 30, 0.45);
  text-decoration: line-through;
  text-decoration-color: rgba(192, 84, 68, 0.55);
}
.md-html :deep(.d-add) {
  background: rgba(64, 158, 87, 0.1);
  box-shadow: inset 3px 0 0 rgba(64, 158, 87, 0.55);
  border-radius: 4px;
  padding: 0 6px;
  margin-left: -6px;
  margin-right: -6px;
}
.md-html :deep(.d-del) {
  color: rgba(38, 37, 30, 0.45);
  text-decoration: line-through;
  text-decoration-color: rgba(192, 84, 68, 0.55);
  background: rgba(192, 84, 68, 0.05);
  border-radius: 4px;
  padding: 0 6px;
  margin-left: -6px;
  margin-right: -6px;
}
.md-html :deep(p) { margin: 0 0 8px; }
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
</style>
