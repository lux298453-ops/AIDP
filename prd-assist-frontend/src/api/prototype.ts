import client from './client'
import type { Result } from '@/types/api'

/** AI 辅助修改请求 */
export interface PrototypeAiEditRequest {
  /** 自然语言修改描述 */
  instruction: string
  /** 可选：指定要修改的元素 */
  targetElement?: string
  /** 可选：前端可视化编辑后的最新 HTML（含手动微调） */
  currentHtml?: string
}

/** AI 辅助修改返回数据 */
export interface PrototypeAiEditResult {
  prototypeId: number
  newHtml: string
  changeSummary: string
}

/**
 * 让 AI 按自然语言指令修改指定原型。
 * AI 调用耗时较长，单独放宽超时到 120s。
 */
export function aiEditPrototype(id: number, data: PrototypeAiEditRequest) {
  return client.post<Result<PrototypeAiEditResult>>(
    `/prototype/${id}/ai-edit`,
    data,
    { timeout: 120000 },
  )
}
