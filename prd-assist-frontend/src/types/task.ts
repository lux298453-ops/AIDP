export type TaskType = 'PRD_GENERATE' | 'PRD_ENHANCE' | 'PRD_REVIEW' | 'PRD_REVIEW_FIX' | 'PROTOTYPE'
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
  inputParams?: string
  resultRefId?: number
  errorMessage?: string
  createdAt: string
  updatedAt: string
}
