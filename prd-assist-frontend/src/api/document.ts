import client from './client'
import type { DocumentItem, DocumentVO, PageResult } from '@/types/document'
import type { Result } from '@/types/api'

export function uploadDocument(file: File) {
  const formData = new FormData(); formData.append('file', file)
  return client.post<Result<DocumentItem>>('/documents/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export function getDocuments() { return client.get<Result<DocumentItem[]>>('/documents') }

export function getDocumentById(id: number) { return client.get<Result<DocumentItem>>(`/documents/${id}`) }

/** 聚合查询我的文档 */
export function getDocumentList(params: { keyword?: string; taskType?: string; page?: number; size?: number }) {
  return client.get<Result<PageResult<DocumentVO>>>('/documents', { params })
}
