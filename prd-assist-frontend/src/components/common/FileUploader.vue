<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const emit = defineEmits<{ uploaded: [file: File] }>()

const uploading = ref(false)

function beforeUpload(file: File) {
  const allowedTypes = ['.xmind', '.doc', '.docx', '.pdf', '.txt', '.md']
  const ext = '.' + file.name.split('.').pop()?.toLowerCase()
  if (!allowedTypes.includes(ext)) {
    ElMessage.error(`不支持的文件类型: ${ext}`)
    return false
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 50MB')
    return false
  }
  emit('uploaded', file)
  return false
}
</script>

<template>
  <el-upload
    drag
    :before-upload="beforeUpload"
    :show-file-list="false"
    accept=".xmind,.doc,.docx,.pdf,.txt,.md"
  >
    <el-icon class="el-icon--upload"><upload-filled /></el-icon>
    <div class="el-upload__text">
      拖拽文件到此处 或 <em>点击上传</em>
    </div>
    <template #tip>
      <div class="el-upload__tip">
        支持 XMind、Word、PDF、TXT、Markdown 文件，不超过 50MB
      </div>
    </template>
  </el-upload>
</template>
