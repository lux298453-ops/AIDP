import { useTaskStore } from '@/stores/task'
import { getTaskSseUrl } from '@/api/task'

export function useTaskPolling(taskId: number) {
  const taskStore = useTaskStore()

  const eventSource = new EventSource(getTaskSseUrl(taskId))

  eventSource.addEventListener('progress', (event) => {
    const data = JSON.parse(event.data)
    taskStore.updateTaskProgress(data.taskId, data.progress, data.message)

    if (data.progress >= 100) {
      eventSource.close()
      taskStore.removeTask(data.taskId)
    }
  })

  eventSource.onerror = () => {
    eventSource.close()
  }

  return {
    stop: () => eventSource.close(),
  }
}
