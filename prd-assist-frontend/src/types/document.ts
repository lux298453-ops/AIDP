export interface DocumentItem {
  id: number; userId: number; fileName: string; fileType: string; filePath: string; fileSize: number; createdAt: string
}

/** 统一文档列表项 */
export interface DocumentVO {
  id: number
  docType: 'PRD' | 'PROTOTYPE' | 'REVIEW'
  title: string
  description?: string
  taskType: string
  taskId?: number
  prdDocumentId?: number
  createdAt: string
}

/** 分页响应 */
export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}
