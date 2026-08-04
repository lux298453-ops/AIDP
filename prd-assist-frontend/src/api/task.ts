import client from './client'
import type { TaskCreateRequest, TaskItem } from '@/types/task'
import type { Result } from '@/types/api'
import type { PageResult } from '@/types/document'
import { getStoredToken, handleSessionExpired, isJwtExpired } from '@/utils/session'

export function createTask(data: TaskCreateRequest) {
  return client.post<Result<TaskItem>>('/tasks', data)
}

export function getTasks(params?: { page?: number; size?: number }) {
  return client.get<Result<PageResult<TaskItem>>>('/tasks', { params })
}

export function getTaskById(id: number) {
  return client.get<Result<TaskItem>>(`/tasks/${id}`)
}

export function getTaskSseUrl(id: number): string {
  const token = getStoredToken()
  if (!token || isJwtExpired(token)) {
    handleSessionExpired()
    return `/api/tasks/${id}/progress`
  }
  const query = token ? `?token=${encodeURIComponent(token)}` : ''
  return `/api/tasks/${id}/progress${query}`
}