<script setup lang="ts">
/**
 * 轻量富文本编辑器（无第三方依赖）
 * - 基于 contenteditable
 * - 工具栏：加粗 / 斜体 / 标题 / 有序无序列表 / 清除格式
 * - model 存「可读纯文本」（\n 换行、列表前缀），展示层转 HTML
 * - 兼容后端 Word 导出的纯文本 content
 */
import { ref, watch, onMounted, nextTick } from 'vue'

const props = defineProps<{
  modelValue: string
  placeholder?: string
  minHeight?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  focus: []
}>()

const editorRef = ref<HTMLElement | null>(null)
const focused = ref(false)
let syncing = false

// ── plain text ↔ HTML ────────────────────────────────────
function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function plainToHtml(text: string): string {
  if (!text) return ''
  // 已是 HTML（含标签）则直接用
  if (/<\/?[a-z][\s\S]*>/i.test(text) && (text.includes('<p') || text.includes('<div') || text.includes('<br') || text.includes('<ul') || text.includes('<ol') || text.includes('<strong') || text.includes('<b'))) {
    return text
  }
  const lines = text.replace(/\r\n/g, '\n').replace(/\\n/g, '\n').split('\n')
  const blocks: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []

  const flushList = () => {
    if (!listType || listItems.length === 0) return
    blocks.push(`<${listType}>${listItems.map(i => `<li>${i}</li>`).join('')}</${listType}>`)
    listType = null
    listItems = []
  }

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i]
    const line = raw
    if (isTableLine(line)) {
      flushList()
      const rows: string[] = []
      while (i < lines.length) {
        if (isTableLine(lines[i])) {
          rows.push(lines[i])
          i++
          continue
        }
        if (lines[i].trim() === '' && i + 1 < lines.length && isTableLine(lines[i + 1])) {
          i++
          continue
        }
        break
      }
      i--
      blocks.push(tableToHtml(rows))
      continue
    }

    // 无序列表
    const ul = line.match(/^\s*[-*•]\s+(.*)$/)
    if (ul) {
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      listItems.push(escapeHtml(ul[1]))
      continue
    }
    // 有序列表
    const ol = line.match(/^\s*\d+[.)、]\s+(.*)$/)
    if (ol) {
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      listItems.push(escapeHtml(ol[1]))
      continue
    }
    flushList()
    if (line.trim() === '') {
      blocks.push('<p><br></p>')
    } else {
      blocks.push(`<p>${escapeHtml(line)}</p>`)
    }
  }
  flushList()
  return blocks.join('') || '<p><br></p>'
}

function isTableLine(line: string): boolean {
  const t = line.trim()
  return t.startsWith('|') && t.endsWith('|') && (t.match(/\|/g) || []).length >= 2
}

function isSeparatorRow(cells: string[]): boolean {
  return cells.length > 0 && cells.every(cell => /^:?-{3,}:?$/.test(cell.trim()))
}

function parseTableRow(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
}

function tableToHtml(lines: string[]): string {
  const rows = lines.map(parseTableRow).filter(row => row.some(cell => cell.length > 0))
  if (!rows.length) return ''
  const header = rows[0]
  const body = rows.slice(1).filter(row => !isSeparatorRow(row))
  const colCount = Math.max(header.length, ...body.map(row => row.length), 1)
  const pad = (row: string[]) => Array.from({ length: colCount }, (_, i) => row[i] || '')
  return `<div class="rte-table-wrap"><table class="rte-table"><thead><tr>${
    pad(header).map(cell => `<th>${escapeHtml(cell)}</th>`).join('')
  }</tr></thead><tbody>${
    body.map(row => `<tr>${pad(row).map(cell => `<td>${escapeHtml(cell)}</td>`).join('')}</tr>`).join('')
  }</tbody></table></div>`
}

function htmlToPlain(html: string): string {
  if (!html) return ''
  const div = document.createElement('div')
  div.innerHTML = html

  const parts: string[] = []

  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      const t = node.textContent || ''
      if (t) parts.push(t)
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return
    const el = node as HTMLElement
    const tag = el.tagName.toLowerCase()

    if (tag === 'br') {
      parts.push('\n')
      return
    }
    if (tag === 'li') {
      const parent = el.parentElement?.tagName.toLowerCase()
      const prefix = parent === 'ol' ? '1. ' : '- '
      // 收集 li 内部纯文本
      const inner: string[] = []
      el.childNodes.forEach(c => {
        if (c.nodeType === Node.TEXT_NODE) inner.push(c.textContent || '')
        else if (c.nodeType === Node.ELEMENT_NODE) {
          const ce = c as HTMLElement
          if (ce.tagName.toLowerCase() === 'br') inner.push('\n')
          else inner.push(ce.innerText || ce.textContent || '')
        }
      })
      parts.push(prefix + inner.join('').trim() + '\n')
      return
    }
    if (tag === 'ul' || tag === 'ol') {
      el.childNodes.forEach(walk)
      return
    }
    if (tag === 'table') {
      const rows = Array.from(el.querySelectorAll('tr'))
        .map(row => Array.from(row.querySelectorAll('th,td')).map(cell => (cell.textContent || '').trim()))
        .filter(row => row.some(cell => cell.length > 0))
      rows.forEach(row => {
        parts.push(`| ${row.join(' | ')} |\n`)
      })
      return
    }
    if (tag === 'p' || tag === 'div' || tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4') {
      el.childNodes.forEach(walk)
      // 块级后换行
      if (!parts.length || !parts[parts.length - 1].endsWith('\n')) parts.push('\n')
      return
    }
    if (tag === 'strong' || tag === 'b') {
      parts.push('**')
      el.childNodes.forEach(walk)
      parts.push('**')
      return
    }
    if (tag === 'em' || tag === 'i') {
      parts.push('*')
      el.childNodes.forEach(walk)
      parts.push('*')
      return
    }
    el.childNodes.forEach(walk)
  }

  div.childNodes.forEach(walk)
  return parts.join('').replace(/\n{3,}/g, '\n\n').replace(/^\n+|\n+$/g, '')
}

function setHtml(html: string) {
  if (!editorRef.value) return
  syncing = true
  editorRef.value.innerHTML = html || '<p><br></p>'
  syncing = false
}

function emitFromDom() {
  if (!editorRef.value || syncing) return
  const plain = htmlToPlain(editorRef.value.innerHTML)
  emit('update:modelValue', plain)
}

function exec(cmd: string, value?: string) {
  editorRef.value?.focus()
  try {
    document.execCommand(cmd, false, value)
  } catch { /* ignore */ }
  emitFromDom()
}

function onInput() { emitFromDom() }
function onFocus() { focused.value = true; emit('focus') }
function onBlur() {
  focused.value = false
  emitFromDom()
}

// 外部 model 变化时同步（避免光标跳动：仅内容真正不同时更新）
watch(
  () => props.modelValue,
  (val) => {
    if (!editorRef.value) return
    const current = htmlToPlain(editorRef.value.innerHTML)
    if (current === (val || '')) return
    // 编辑中不强制覆盖，防止打断输入
    if (focused.value) return
    setHtml(plainToHtml(val || ''))
  },
)

onMounted(async () => {
  await nextTick()
  setHtml(plainToHtml(props.modelValue || ''))
})

defineExpose({
  focus: () => editorRef.value?.focus(),
})
</script>

<template>
  <div class="rte" :class="{ 'is-focused': focused }">
    <div class="rte-toolbar" @mousedown.prevent>
      <button type="button" class="rte-btn" title="加粗" @click="exec('bold')"><b>B</b></button>
      <button type="button" class="rte-btn" title="斜体" @click="exec('italic')"><i>I</i></button>
      <span class="rte-sep" />
      <button type="button" class="rte-btn" title="一级标题" @click="exec('formatBlock', 'h3')">H</button>
      <button type="button" class="rte-btn" title="正文" @click="exec('formatBlock', 'p')">正文</button>
      <span class="rte-sep" />
      <button type="button" class="rte-btn" title="无序列表" @click="exec('insertUnorderedList')">• 列表</button>
      <button type="button" class="rte-btn" title="有序列表" @click="exec('insertOrderedList')">1. 列表</button>
      <span class="rte-sep" />
      <button type="button" class="rte-btn" title="清除格式" @click="exec('removeFormat')">清除</button>
    </div>
    <div
      ref="editorRef"
      class="rte-body"
      contenteditable="true"
      :data-placeholder="placeholder || '在此编辑章节内容…'"
      :style="{ minHeight: minHeight || '160px' }"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
    />
  </div>
</template>

<style scoped>
.rte {
  border: 1px solid rgba(38, 37, 30, 0.12);
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
  transition: border-color .15s, box-shadow .15s;
}
.rte.is-focused {
  border-color: rgba(38, 37, 30, 0.35);
  box-shadow: 0 0 0 3px rgba(38, 37, 30, 0.06);
}
.rte-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  padding: 6px 8px;
  background: #f7f7f4;
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
}
.rte-btn {
  border: none;
  background: transparent;
  color: rgba(38, 37, 30, 0.7);
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  line-height: 1.4;
  font-family: inherit;
}
.rte-btn:hover { background: #e6e5e0; color: #26251e; }
.rte-btn b { font-weight: 700; }
.rte-btn i { font-style: italic; }
.rte-sep {
  width: 1px;
  height: 16px;
  background: rgba(38, 37, 30, 0.12);
  margin: 0 4px;
}
.rte-body {
  padding: 14px 16px;
  outline: none;
  font-size: 14px;
  line-height: 1.75;
  color: #26251e;
  word-break: break-word;
}
.rte-body:empty::before,
.rte-body:has(> br:only-child)::before {
  content: attr(data-placeholder);
  color: rgba(38, 37, 30, 0.35);
  pointer-events: none;
}
.rte-body :deep(p) { margin: 0 0 8px; }
.rte-body :deep(h3) {
  margin: 4px 0 10px;
  font-size: 16px;
  font-weight: 600;
  color: #26251e;
}
.rte-body :deep(ul),
.rte-body :deep(ol) {
  margin: 6px 0 10px;
  padding-left: 22px;
}
.rte-body :deep(li) { margin: 3px 0; }
.rte-body :deep(.rte-table-wrap) {
  overflow-x: auto;
  margin: 8px 0 12px;
}
.rte-body :deep(.rte-table) {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  background: #fff;
  border: 1px solid rgba(38, 37, 30, 0.12);
  font-size: 13px;
}
.rte-body :deep(.rte-table th),
.rte-body :deep(.rte-table td) {
  border: 1px solid rgba(38, 37, 30, 0.12);
  padding: 8px 10px;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
}
.rte-body :deep(.rte-table th) {
  background: #f7f7f4;
  font-weight: 600;
}
.rte-body :deep(strong),
.rte-body :deep(b) { font-weight: 600; }
</style>
