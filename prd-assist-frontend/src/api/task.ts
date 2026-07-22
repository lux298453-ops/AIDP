import client from './client'
import type { TaskCreateRequest, TaskItem } from '@/types/task'
import type { Result } from '@/types/api'

export function createTask(data: TaskCreateRequest) {
  return client.post<Result<TaskItem>>('/tasks', data)
}

export function getTasks() {
  return client.get<Result<TaskItem[]>>('/tasks')
}

export function getTaskById(id: number) {
  return client.get<Result<TaskItem>>(`/tasks/${id}`)
}

export function getTaskSseUrl(id: number): string {
  const token = localStorage.getItem('token')
  return `/api/tasks/${id}/progress?token=${token}`
}
