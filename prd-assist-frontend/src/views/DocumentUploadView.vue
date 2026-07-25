<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadDocument, getDocuments } from '@/api/document'
import type { DocumentItem } from '@/types/document'

const documents = ref<DocumentItem[]>([])
const uploading = ref(false)

async function handleUpload(file: File) {
  uploading.value = true
  try {
    await uploadDocument(file)
    ElMessage.success('上传成功')
    loadDocuments()
  } catch {
    // handled by interceptor
  } finally {
    uploading.value = false
  }
}

async function loadDocuments() {
  try {
    const res = await getDocuments()
    documents.value = res.data.data || []
  } catch {
    // handled
  }
}

loadDocuments()
</script>

<template>
  <div class="document-page">
    <h3>文档管理</h3>
    <el-card>
      <el-upload
        :before-upload="(file: File) => { handleUpload(file); return false }"
        :show-file-list="false"
        accept=".xmind,.doc,.docx,.pdf,.txt,.md"
      >
        <el-button type="primary" :loading="uploading">上传文档</el-button>
      </el-upload>

      <el-table :data="documents" style="margin-top: 20px" empty-text="暂无文档">
        <el-table-column prop="fileName" label="文件名" />
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column prop="fileSize" label="大小" width="100" :formatter="(row: DocumentItem) => (row.fileSize / 1024).toFixed(1) + ' KB'" />
        <el-table-column prop="createdAt" label="上传时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.document-page {
  padding: 32px 48px;
}
.document-page h3 {
  margin-bottom: 20px;
  font-size: 22px;
  font-weight: 400;
  color: #26251e;
  letter-spacing: -0.11px;
}
</style>
