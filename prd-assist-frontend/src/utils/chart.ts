/**
 * 图表（PlantUML / Mermaid）内容解析与渲染工具。
 * - 预览统一走后端 /api/charts/render，保证页面展示与 Word 导出逻辑一致
 * - PlantUML 是当前主格式，Mermaid 兼容旧文档
 */
import client from '@/api/client'

export type ChartLang = 'plantuml' | 'mermaid'
export type ChartFormat = 'png' | 'svg'

export function detectChartLang(code: string): ChartLang {
  return code && (code.includes('@startuml') || code.includes('@enduml'))
    ? 'plantuml'
    : 'mermaid'
}

export function detectContentLang(content: string): ChartLang | null {
  if (!content) return null
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
  if (/```plantuml/i.test(text) || /@startuml/i.test(text)) return 'plantuml'
  if (/```mermaid/i.test(text) || /(^|\n)\s*(flowchart|graph|sequenceDiagram)\b/i.test(text)) return 'mermaid'
  return null
}

export function hasChart(content: string | undefined | null): boolean {
  return detectContentLang(content || '') !== null
}

export function extractChartCode(content: string): string | null {
  if (!content) return null
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')

  const pumlFence = text.match(/```plantuml[ \t]*\n([\s\S]*?)```/i)
  if (pumlFence) return pumlFence[1].trim()
  const rawPuml = text.match(/@startuml[\s\S]*?@enduml/i)
  if (rawPuml) return rawPuml[0].trim()

  const fence = text.match(/```mermaid[ \t]*\n([\s\S]*?)```/i)
  if (fence) return fence[1].trim()

  const lines = text.split('\n')
  let start = -1
  for (let i = 0; i < lines.length; i++) {
    if (/^\s*(flowchart|graph|sequenceDiagram)\b/i.test(lines[i])) {
      start = i
      break
    }
  }
  if (start < 0) return null

  const buf: string[] = []
  for (let i = start; i < lines.length; i++) {
    const t = lines[i].trim()
    if (
      i > start &&
      t &&
      !/^(flowchart|graph|sequenceDiagram|--|==>|-->|\[|\]|\(|\)|subgraph|end|participant|Note|style |classDef|[A-Za-z][\w]*)/i.test(t) &&
      !/(--|==>|-->|\[|\]|\(|\))/.test(t) &&
      !/^[A-Za-z][\w]*([\[{(].*)?$/.test(t) &&
      !/^\s*$/.test(lines[i])
    ) {
      if (buf.length > 2) break
    }
    buf.push(lines[i])
  }
  const code = buf.join('\n').trim()
  return code || null
}

export function extractChartCaption(content: string): string {
  if (!content) return ''
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
  let rest = text.replace(/```(?:plantuml|mermaid)[ \t]*\n[\s\S]*?```/gi, '').trim()
  rest = rest.replace(/@startuml[\s\S]*?@enduml/gi, '').trim()
  if (/^\s*(flowchart|graph|sequenceDiagram)\b/i.test(rest)) {
    const lines = rest.split('\n')
    let i = 0
    while (i < lines.length) {
      const t = lines[i].trim()
      if (
        i === 0 ||
        /(--|==>|-->|subgraph|end|participant|Note|style |classDef)/i.test(t) ||
        /^[A-Za-z][\w]*([\[{(].*)?$/.test(t) ||
        t === ''
      ) {
        i++
        continue
      }
      break
    }
    rest = lines.slice(i).join('\n').trim()
  }
  return rest
}

export function replaceChartInContent(content: string, newCode: string): string {
  const lang = detectChartLang(newCode)
  const block = lang === 'plantuml'
    ? `\`\`\`plantuml\n${newCode.trim()}\n\`\`\``
    : `\`\`\`mermaid\n${newCode.trim()}\n\`\`\``

  const text = (content || '').replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
  // 原位替换：只把原有图表块换成新代码，章节其余文字一律原样保留，
  // 避免旧实现“图表 + 重组说明”导致图文混合章节的文字被挪位或删除
  const chartPatterns = [
    /```(?:plantuml|mermaid)[ \t]*\n[\s\S]*?```/i,
    /@startuml[\s\S]*?@enduml/i,
  ]
  for (const pattern of chartPatterns) {
    if (pattern.test(text)) {
      return text.replace(pattern, block)
    }
  }
  // 原内容没有可识别的图表块：新图放最前，原文完整保留在后
  const rest = text.trim()
  return rest ? `${block}\n\n${rest}` : block
}

export async function renderChartImageUrl(code: string, lang: ChartLang, format: ChartFormat = 'png'): Promise<string | null> {
  try {
    const res = await client.post('/charts/render', { code, lang, format }, {
      responseType: 'blob',
      silentError: true,
    } as any)
    if (res.status !== 200) return null
    return URL.createObjectURL(res.data as Blob)
  } catch (e) {
    console.warn('renderChartImageUrl failed', e)
    return null
  }
}

export async function renderChartSvgHtml(code: string, lang: ChartLang): Promise<string | null> {
  try {
    const res = await client.post('/charts/render', { code, lang, format: 'svg' }, {
      responseType: 'blob',
      silentError: true,
    } as any)
    if (res.status !== 200) return null
    const raw = await (res.data as Blob).text()
    return raw
      .replace(/^\s*<\?xml[\s\S]*?\?>\s*/i, '')
      .replace(/^\s*<!DOCTYPE[\s\S]*?>\s*/i, '')
      .trim() || null
  } catch (e) {
    console.warn('renderChartSvgHtml failed', e)
    return null
  }
}

export async function chartToPngBase64(code: string, lang: ChartLang): Promise<string | null> {
  try {
    const res = await client.post('/charts/render', { code, lang, format: 'png' }, {
      responseType: 'blob',
      silentError: true,
    } as any)
    if (res.status !== 200) return null
    const blob = res.data as Blob
    return await new Promise<string | null>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = String(reader.result || '')
        resolve(dataUrl.replace(/^data:image\/png;base64,/, '') || null)
      }
      reader.onerror = () => resolve(null)
      reader.readAsDataURL(blob)
    })
  } catch (e) {
    console.warn('chartToPngBase64 failed', e)
    return null
  }
}
