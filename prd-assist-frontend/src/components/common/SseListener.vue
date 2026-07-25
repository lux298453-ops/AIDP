<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getTaskSseUrl } from '@/api/task'

const props = defineProps<{ taskId: number }>()

const progress = ref(0)
const message = ref('')
let eventSource: EventSource | null = null

onMounted(() => {
  eventSource = new EventSource(getTaskSseUrl(props.taskId))

  eventSource.addEventListener('progress', (event) => {
    const data = JSON.parse(event.data)
    progress.value = data.progress
    message.value = data.message
  })

  eventSource.onerror = () => {
    eventSource?.close()
  }
})

onUnmounted(() => {
  eventSource?.close()
})
</script>

<template>
  <div>
    <el-progress :percentage="progress" :stroke-width="6" />
    <p v-if="message" style="color: #909399; font-size: 13px">{{ message }}</p>
  </div>
</template>
