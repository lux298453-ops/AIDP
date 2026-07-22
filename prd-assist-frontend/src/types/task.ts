export type TaskType = 'PRD_GENERATE' | 'PRD_ENHANCE' | 'PRD_REVIEW' | 'PROTOTYPE_GENERATE'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface TaskCreateRequest {
  taskType: TaskType
  inputData?: string
}

export interface TaskItem {
  id: number
  userId: number
  taskType: TaskType
  status: TaskStatus
  progress: number
  inputData?: string
  outputData?: string
  errorMsg?: string
  createdAt: string
  updatedAt: string
}
