import client from './client'
import type { DocumentItem, DocumentVO, PageResult } from '@/types/document'

export function uploadDocument(file: File) {
  const formData = new FormData(); formData.append('file', file)
  return client.post<DocumentItem>('/documents/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export function getDocuments() { return client.get<DocumentItem[]>('/documents') }

export function getDocumentById(id: number) { return client.get<DocumentItem>(`/documents/${id}`) }

/** 聚合查询我的文档 */
export function getDocumentList(params: { keyword?: string; taskType?: string; page?: number; size?: number }) {
  return client.get<PageResult<DocumentVO>>('/documents', { params })
}
