/**
 * 图表（PlantUML / Mermaid）内容解析与渲染工具。
 * - 渲染统一走后端 /api/charts/render，保证页面展示与 Word 导出完全一致
 * - PlantUML 为主要格式，Mermaid 兼容旧文档
 */
import client from '@/api/client'

export type ChartLang = 'plantuml' | 'mermaid'

/** 判断源码语言 */
export function detectChartLang(code: string): ChartLang {
  return code && (code.includes('@startuml') || code.includes('@enduml'))
    ? 'plantuml'
    : 'mermaid'
}

/** 判断 content 是否含图表及语言；无图表返回 null */
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

/** 从章节 content 提取第一段图表源码 */
export function extractChartCode(content: string): string | null {
  if (!content) return null
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')

  const pumlFence = text.match(/```plantuml[ \t]*\n([\s\S]*?)```/i)
  if (pumlFence) return pumlFence[1].trim()
  const rawPuml = text.match(/@startuml[\s\S]*?@enduml/i)
  if (rawPuml) return rawPuml[0].trim()

  const fence = text.match(/```mermaid[ \t]*\n([\s\S]*?)```/i)
  if (fence) return fence[1].trim()

  // 裸 flowchart / sequenceDiagram
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
    if (i > start && t && !/^(flowchart|graph|sequenceDiagram|--|==>|-->|\[|\]|\(|\)|subgraph|end|participant|Note|style |classDef|[A-Za-z][\w]*)/i.test(t)
      && !/(--|==>|-->|\[|\]|\(|\))/.test(t)
      && !/^[A-Za-z][\w]*([\[{(].*)?$/.test(t)
      && !/^\s*$/.test(lines[i])) {
      if (buf.length > 2) break
    }
    buf.push(lines[i])
  }
  const code = buf.join('\n').trim()
  return code || null
}

/** 去掉图表代码块后的说明文字 */
export function extractChartCaption(content: string): string {
  if (!content) return ''
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
  // 去掉 plantuml / mermaid 围栏
  let rest = text.replace(/```(?:plantuml|mermaid)[ \t]*\n[\s\S]*?```/gi, '').trim()
  // 去掉裸 @startuml 块
  rest = rest.replace(/@startuml[\s\S]*?@enduml/gi, '').trim()
  // 去掉裸 flowchart 块（粗略）
  if (/^\s*(flowchart|graph|sequenceDiagram)\b/i.test(rest)) {
    const lines = rest.split('\n')
    let i = 0
    while (i < lines.length) {
      const t = lines[i].trim()
      if (i === 0 || /(--|==>|-->|subgraph|end|participant|Note|style |classDef)/i.test(t)
        || /^[A-Za-z][\w]*([\[{(].*)?$/.test(t) || t === '') {
        i++
        continue
      }
      break
    }
    rest = lines.slice(i).join('\n').trim()
  }
  return rest
}

/** 用新图表源码替换 content 中的图，保留说明文字 */
export function replaceChartInContent(content: string, newCode: string): string {
  const lang = detectChartLang(newCode)
  const caption = extractChartCaption(content)
  const block = lang === 'plantuml'
    ? '```plantuml\n' + newCode.trim() + '\n```'
    : '```mermaid\n' + newCode.trim() + '\n```'
  if (caption) return block + '\n\n' + caption
  return block
}

/** 后端渲染图表 → blob 对象 URL（供 <img> 展示） */
export async function renderChartImageUrl(code: string, lang: ChartLang): Promise<string | null> {
  try {
    const res = await client.post('/charts/render', { code, lang }, {
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

/** 后端渲染图表 → PNG base64（供 Word 导出） */
export async function chartToPngBase64(code: string, lang: ChartLang): Promise<string | null> {
  try {
    const res = await client.post('/charts/render', { code, lang }, {
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
