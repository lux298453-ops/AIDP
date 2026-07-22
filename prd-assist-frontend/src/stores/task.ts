import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useTaskStore = defineStore('task', () => {
  const activeTasks = ref<Map<number, { progress: number; message: string }>>(new Map())

  function updateTaskProgress(taskId: number, progress: number, message: string) {
    activeTasks.value.set(taskId, { progress, message })
  }

  function removeTask(taskId: number) {
    activeTasks.value.delete(taskId)
  }

  return { activeTasks, updateTaskProgress, removeTask }
})
