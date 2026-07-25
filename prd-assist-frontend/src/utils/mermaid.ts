/**
 * Mermaid 内容解析 / 拼接 / 导出 PNG 工具
 */

export function hasMermaid(content: string | undefined | null): boolean {
  if (!content) return false
  const c = content.toLowerCase()
  return c.includes('```mermaid')
    || /(^|\n)\s*(flowchart|graph|sequencediagram)\b/i.test(content)
}

/** 从章节 content 中提取第一段 mermaid 源码 */
export function extractMermaidCode(content: string): string | null {
  if (!content) return null
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
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
      // 可能进入中文说明
      if (buf.length > 2) break
    }
    buf.push(lines[i])
  }
  const code = buf.join('\n').trim()
  return code || null
}

/** 去掉 mermaid 代码块后的说明文字 */
export function extractMermaidCaption(content: string): string {
  if (!content) return ''
  const text = content.replace(/\r\n/g, '\n').replace(/\\n/g, '\n')
  // 去掉 ```mermaid ... ```
  let rest = text.replace(/```mermaid[ \t]*\n[\s\S]*?```/gi, '').trim()
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

/** 用新 mermaid 源码替换 content 中的图，保留说明文字 */
export function replaceMermaidInContent(content: string, newCode: string): string {
  const caption = extractMermaidCaption(content)
  const block = '```mermaid\n' + newCode.trim() + '\n```'
  if (caption) return block + '\n\n' + caption
  return block
}

/**
 * 规范化 SVG：补 xmlns、宽高、viewBox，便于 Image + canvas 光栅化。
 */
function normalizeSvgForCanvas(svgHtml: string): { svg: string; width: number; height: number } {
  let svg = svgHtml.trim()
  // mermaid 有时返回外层 wrapper，取第一个 <svg>
  const svgMatch = svg.match(/<svg[\s\S]*<\/svg>/i)
  if (svgMatch) svg = svgMatch[0]

  if (!/xmlns=/.test(svg)) {
    svg = svg.replace(/<svg\b/i, '<svg xmlns="http://www.w3.org/2000/svg"')
  }
  if (!/xmlns:xlink=/.test(svg)) {
    svg = svg.replace(/<svg\b/i, '<svg xmlns:xlink="http://www.w3.org/1999/xlink"')
  }

  // 解析宽高
  let width = 0
  let height = 0
  const wAttr = svg.match(/\bwidth="([\d.]+)(px)?"/i)
  const hAttr = svg.match(/\bheight="([\d.]+)(px)?"/i)
  const vbAttr = svg.match(/\bviewBox="\s*([\d.-]+)\s+([\d.-]+)\s+([\d.]+)\s+([\d.]+)\s*"/i)
  if (wAttr) width = parseFloat(wAttr[1])
  if (hAttr) height = parseFloat(hAttr[1])
  if ((!width || !height) && vbAttr) {
    width = width || parseFloat(vbAttr[3])
    height = height || parseFloat(vbAttr[4])
  }
  if (!width || width < 1) width = 800
  if (!height || height < 1) height = 400

  // 确保有显式 width/height（部分浏览器 Image 加载 SVG 时否则 naturalWidth=0）
  if (wAttr) {
    svg = svg.replace(/\bwidth="[^"]*"/i, `width="${width}"`)
  } else {
    svg = svg.replace(/<svg\b/i, `<svg width="${width}"`)
  }
  if (hAttr) {
    svg = svg.replace(/\bheight="[^"]*"/i, `height="${height}"`)
  } else {
    svg = svg.replace(/<svg\b/i, `<svg height="${height}"`)
  }
  if (!vbAttr) {
    svg = svg.replace(/<svg\b/i, `<svg viewBox="0 0 ${width} ${height}"`)
  }

  // 白底（部分主题透明，Word 里发黑）
  if (!/<rect[^>]*fill\s*=\s*["']#?fff/i.test(svg)) {
    svg = svg.replace(
      /(<svg[^>]*>)/i,
      `$1<rect width="100%" height="100%" fill="#ffffff"/>`,
    )
  }

  return { svg, width, height }
}

/**
 * SVG 字符串 → PNG base64（不含 data: 前缀）。
 * 使用 data: URL，比 blob URL 对 mermaid 更稳。
 */
/** scale=3 → 导出约 3× 屏幕像素，Word 中仍清晰 */
export async function svgToPngBase64(svgHtml: string, scale = 3): Promise<string | null> {
  try {
    const { svg, width, height } = normalizeSvgForCanvas(svgHtml)

    const encoded = encodeURIComponent(svg)
      .replace(/'/g, '%27')
      .replace(/"/g, '%22')
    const dataUrl = `data:image/svg+xml;charset=utf-8,${encoded}`

    const img = new Image()
    img.crossOrigin = 'anonymous'
    const loaded = new Promise<void>((resolve, reject) => {
      img.onload = () => resolve()
      img.onerror = () => reject(new Error('svg image load failed'))
    })
    img.src = dataUrl
    await loaded

    const w = Math.max(img.naturalWidth || img.width || width, 1)
    const h = Math.max(img.naturalHeight || img.height || height, 1)
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(w * scale)
    canvas.height = Math.round(h * scale)
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.setTransform(scale, 0, 0, scale, 0, 0)
    ctx.drawImage(img, 0, 0, w, h)

    const pngDataUrl = canvas.toDataURL('image/png')
    return pngDataUrl.replace(/^data:image\/png;base64,/, '')
  } catch (e) {
    console.warn('svgToPngBase64 failed (data-url path)', e)
    // 回退 blob URL
    try {
      const { svg, width, height } = normalizeSvgForCanvas(svgHtml)
      const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const img = new Image()
      const loaded = new Promise<void>((resolve, reject) => {
        img.onload = () => resolve()
        img.onerror = () => reject(new Error('svg blob load failed'))
      })
      img.src = url
      await loaded
      const w = Math.max(img.naturalWidth || img.width || width, 1)
      const h = Math.max(img.naturalHeight || img.height || height, 1)
      const canvas = document.createElement('canvas')
      canvas.width = Math.round(w * scale)
      canvas.height = Math.round(h * scale)
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        URL.revokeObjectURL(url)
        return null
      }
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.setTransform(scale, 0, 0, scale, 0, 0)
      ctx.drawImage(img, 0, 0, w, h)
      URL.revokeObjectURL(url)
      return canvas.toDataURL('image/png').replace(/^data:image\/png;base64,/, '')
    } catch (e2) {
      console.warn('svgToPngBase64 failed (blob path)', e2)
      return null
    }
  }
}

/** 用 mermaid.js 渲染源码得到 SVG HTML（导出时关闭 htmlLabels，避免 foreignObject 污染 canvas） */
export async function renderMermaidToSvg(code: string): Promise<string> {
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
    flowchart: { curve: 'basis', htmlLabels: false, padding: 12 },
  } as any)
  const id = `export-mmd-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const { svg } = await mermaid.render(id, code.trim())
  return svg
}

/**
 * 一站式：mermaid 源码 → PNG base64。
 * 供导出调用；失败返回 null，由后端兜底渲染。
 */
export async function mermaidCodeToPngBase64(code: string): Promise<string | null> {
  if (!code?.trim()) return null
  try {
    const svg = await renderMermaidToSvg(code)
    return await svgToPngBase64(svg)
  } catch (e) {
    console.warn('mermaidCodeToPngBase64 failed', e)
    return null
  }
}
