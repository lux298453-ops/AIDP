import client from './client'
import type { TaskCreateRequest, TaskItem } from '@/types/task'

export function createTask(data: TaskCreateRequest) {
  return client.post<TaskItem>('/tasks', data)
}

export function getTasks() {
  return client.get<TaskItem[]>('/tasks')
}

export function getTaskById(id: number) {
  return client.get<TaskItem>(`/tasks/${id}`)
}

export function getTaskSseUrl(id: number): string {
  const token = localStorage.getItem('token')
  return `/api/tasks/${id}/progress?token=${token}`
}
