<script setup lang="ts">
defineProps<{
  progress: number
  message?: string
}>()
</script>

<template>
  <div class="task-progress">
    <el-progress :percentage="progress" :stroke-width="8" :status="progress >= 100 ? 'success' : undefined" />
    <p v-if="message" class="progress-message">{{ message }}</p>
  </div>
</template>

<style scoped>
.task-progress {
  margin: 12px 0;
}
.progress-message {
  color: #909399;
  font-size: 13px;
  margin-top: 8px;
}
</style>
