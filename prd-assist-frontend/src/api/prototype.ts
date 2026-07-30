import client from './client'
import type { Result } from '@/types/api'

/** AI 辅助修改请求 */
export interface PrototypeAiEditRequest {
  /** 自然语言修改描述 */
  instruction: string
  /** 可选：指定要修改的元素 */
  targetElement?: string
  /** 多页原型当前页索引，用于后端保存当前页修改 */
  pageIndex?: number
  /** 可选：前端可视化编辑后的最新 HTML（含手动微调） */
  currentHtml?: string
}

/** AI 辅助修改返回数据 */
export interface PrototypeAiEditResult {
  prototypeId: number
  newHtml: string
  changeSummary: string
}

export interface PrototypeAiEditSnapshotRequest {
  currentHtml: string
  pageIndex?: number
  changeSummary?: string
}

export interface PrototypeResultData {
  id: number
  content: string
  prototypeType?: string
  platform?: string
}

/**
 * 让 AI 按自然语言指令修改指定原型。
 * AI 调用耗时较长，单独放宽超时到 120s。
 */
export function aiEditPrototype(id: number, data: PrototypeAiEditRequest) {
  return client.post<Result<PrototypeAiEditResult>>(
    `/prototype/${id}/ai-edit`,
    data,
    { timeout: 300000, silentError: true } as any,
  )
}

export function startPrototypeAiEditStream(id: number, data: PrototypeAiEditRequest) {
  return client.post<Result<number>>(
    `/prototype/${id}/ai-edit/stream`,
    data,
    { timeout: 60000, silentError: true } as any,
  )
}

export function savePrototypeAiEditSnapshot(id: number, data: PrototypeAiEditSnapshotRequest) {
  return client.post<Result<PrototypeAiEditResult>>(
    `/prototype/${id}/ai-edit/snapshot`,
    data,
    { timeout: 60000, silentError: true } as any,
  )
}

export function getPrototype(id: number) {
  return client.get<Result<PrototypeResultData>>(
    `/prototype/${id}`,
    { silentError: true } as any,
  )
}
