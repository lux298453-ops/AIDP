/**
 * Markdown 渲染纯函数（无 Vue 依赖）：
 * 供 MarkdownContent.vue（展示）与 DiffContent.vue（diff 高亮）共用。
 */

export function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function inlineFormat(s: string): string {
  let t = escapeHtml(s)
  t = t.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  t = t.replace(/\*(.+?)\*/g, '<em>$1</em>')
  t = t.replace(/`([^`]+)`/g, '<code>$1</code>')
  return t
}

export function isTableLine(line: string): boolean {
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
  return `<div class="md-table-wrap"><table class="md-table"><thead><tr>${
    pad(header).map(cell => `<th>${inlineFormat(cell)}</th>`).join('')
  }</tr></thead><tbody>${
    body.map(row => `<tr>${pad(row).map(cell => `<td>${inlineFormat(cell)}</td>`).join('')}</tr>`).join('')
  }</tbody></table></div>`
}

/** 普通文本 → HTML 段落/列表/表格/标题/引用 */
export function plainToHtml(text: string): string {
  if (!text.trim()) return ''
  const lines = text.split('\n')
  const parts: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let items: string[] = []

  const flushList = () => {
    if (!listType) return
    parts.push(`<${listType}>${items.map(i => `<li>${i}</li>`).join('')}</${listType}>`)
    listType = null
    items = []
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
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
      parts.push(tableToHtml(rows))
      continue
    }

    const ul = line.match(/^\s*[-*•]\s+(.*)$/)
    if (ul) {
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      items.push(inlineFormat(ul[1]))
      continue
    }
    const ol = line.match(/^\s*\d+[.)、]\s+(.*)$/)
    if (ol) {
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      items.push(inlineFormat(ol[1]))
      continue
    }
    flushList()
    if (line.trim() === '') {
      parts.push('<p class="blank"><br></p>')
    } else if (/^#{1,3}\s+/.test(line)) {
      const level = line.match(/^(#{1,3})/)?.[1].length || 2
      const t = line.replace(/^#{1,3}\s+/, '')
      parts.push(`<h${level + 2}>${inlineFormat(t)}</h${level + 2}>`)
    } else if (line.trim().startsWith('>')) {
      parts.push(`<blockquote>${inlineFormat(line.replace(/^\s*>\s?/, ''))}</blockquote>`)
    } else {
      parts.push(`<p>${inlineFormat(line)}</p>`)
    }
  }
  flushList()
  return parts.join('')
}
