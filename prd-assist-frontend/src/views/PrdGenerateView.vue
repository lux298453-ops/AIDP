<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { getTaskById } from '@/api/task'
import client from '@/api/client'

const activeTab = ref<'direct' | 'xmind'>('direct')
const funcName = ref('')
const description = ref('')
const template = ref('STANDARD')
const detailLevel = ref('CONCISE')

const xmindInput = ref<HTMLInputElement | null>(null)
const xmindFile = ref<File | null>(null)

function triggerXmindUpload() { xmindInput.value?.click() }
function onXmindFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    const f = input.files[0]
    if (!f.name.toLowerCase().endsWith('.xmind')) { ElMessage.warning('仅支持 .xmind 文件'); return }
    xmindFile.value = f; ElMessage.success(`已选择: ${f.name}`)
  }
}

const loading = ref(false)
const progress = ref(0)
const result = ref('')
let taskId: number | null = null

const canGenerate = computed(() => {
  if (activeTab.value === 'direct') return funcName.value.trim() && description.value.trim()
  return xmindFile.value !== null
})

async function handleGenerate() {
  loading.value = true; result.value = ''; progress.value = 0; taskId = null
  try {
    if (activeTab.value === 'direct') {
      if (!funcName.value.trim()) { ElMessage.warning('请输入功能名称'); loading.value = false; return }
      if (!description.value.trim()) { ElMessage.warning('请输入需求描述'); loading.value = false; return }
      const res = await client.post('/prd/generate', {
        featureName: funcName.value, description: description.value,
        template: template.value, detailLevel: detailLevel.value
      })
      taskId = res.data.data.taskId
    } else {
      if (!xmindFile.value) { ElMessage.warning('请上传 XMind 文件'); loading.value = false; return }
      const fd = new FormData()
      fd.append('file', xmindFile.value); fd.append('template', template.value); fd.append('detailLevel', detailLevel.value)
      const res = await client.post('/prd/generate/xmind', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
      taskId = res.data.data.taskId
    }
    pollTask()
  } catch { loading.value = false }
}

async function pollTask() {
  if (!taskId) return
  const timer = setInterval(async () => {
    try {
      const r = await getTaskById(taskId!)
      const st = r.data.data.status
      if (st === 'RUNNING') progress.value = Math.min(progress.value + 8, 85)
      else if (st === 'SUCCESS') {
        progress.value = 100; loading.value = false; clearInterval(timer)
        ElMessage.success('PRD 生成完成')
        const refId = r.data.data.resultRefId
        if (refId) {
          try {
            const doc = await client.get(`/prd/${refId}`)
            const c = doc.data.data?.content
            if (c) { try { result.value = fmt(JSON.parse(c)) } catch { result.value = c } }
          } catch { result.value = '请在「我的文档」中查看生成结果' }
        }
      } else if (st === 'FAILED') {
        loading.value = false; clearInterval(timer)
        ElMessage.error(r.data.data.errorMessage || '生成失败')
      }
    } catch { /* ignore */ }
  }, 1500)
}

function fmt(j: any): string {
  let t = ''
  if (j.title) t += `<h3>${j.title}</h3>`
  if (j.summary) t += `<p><em>${j.summary}</em></p>`
  if (j.chapters) for (const c of j.chapters) t += `<h4>${c.title||''}</h4><p>${(c.content||'').replace(/\n/g,'<br>')}</p>`
  return t || JSON.stringify(j)
}
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">PRD生成</h2>
          <el-icon size="18" color="#909399"><Clock /></el-icon>
        </div>
        <div class="mode-tabs">
          <div class="mode-tab" :class="{ active: activeTab === 'direct' }" @click="activeTab = 'direct'">直接生成PRD</div>
          <div class="mode-tab" :class="{ active: activeTab === 'xmind' }" @click="activeTab = 'xmind'">XMIND生成PRD</div>
        </div>

        <template v-if="activeTab === 'direct'">
          <div class="form-group">
            <label class="form-label">功能名 <span class="required">*</span></label>
            <el-input v-model="funcName" placeholder="请输入功能名称，必填，最多50字" maxlength="50" show-word-limit />
          </div>
          <div class="form-group">
            <label class="form-label">需求描述 <span class="required">*</span></label>
            <el-input v-model="description" type="textarea" :rows="8" placeholder="请描述您的产品需求，最多500字" maxlength="500" show-word-limit />
          </div>
        </template>

        <template v-if="activeTab === 'xmind'">
          <div class="form-group">
            <label class="form-label">上传XMind文件</label>
            <input ref="xmindInput" type="file" accept=".xmind" style="display:none" @change="onXmindFileSelected" />
            <div class="upload-xmind" @click="triggerXmindUpload">
              <el-icon size="40" :color="xmindFile ? '#67c23a' : '#c0c4cc'"><UploadFilled /></el-icon>
              <p class="upload-text">{{ xmindFile ? xmindFile.name : '点击上传 .xmind 文件' }}</p>
              <p class="upload-hint">{{ xmindFile ? `${(xmindFile.size / 1024).toFixed(1)} KB` : '或将文件拖拽到此处' }}</p>
            </div>
          </div>
        </template>

        <div class="form-group">
          <label class="form-label">模板选择</label>
          <el-select v-model="template" style="width:100%">
            <el-option label="标准模板" value="STANDARD" />
          </el-select>
        </div>
        <div class="form-group">
          <label class="form-label">详略程度</label>
          <el-radio-group v-model="detailLevel" style="display:flex;gap:12px">
            <el-radio value="CONCISE">简洁</el-radio>
            <el-radio value="DETAILED">详细</el-radio>
          </el-radio-group>
        </div>

        <el-button class="btn-generate" :loading="loading" :disabled="!canGenerate" @click="handleGenerate">一键生成</el-button>
        <el-progress v-if="loading" :percentage="progress" :stroke-width="6" style="margin-top:12px" />
      </div>

      <div class="preview-panel">
        <template v-if="result">
          <div class="result-content" v-html="result" />
        </template>
        <template v-else-if="loading">
          <div class="preview-placeholder">
            <el-icon size="60" color="#409eff"><Loading /></el-icon>
            <p class="placeholder-title">AI 正在生成 PRD...</p>
            <p class="placeholder-desc">请稍候，DeepSeek 正在为您撰写专业文档</p>
          </div>
        </template>
        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="#dcdfe6"><Document /></el-icon>
            <p class="placeholder-title">专业PRD文档即将呈现</p>
            <p class="placeholder-desc">AI将为您生成包含需求分析、功能设计、交互流程等完整内容的专业文档</p>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-container { padding: 24px 48px; height: 100%; box-sizing: border-box; }
.workspace { display: flex; height: 100%; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 12px rgba(0,0,0,0.06); }
.config-panel { width: 420px; flex-shrink: 0; padding: 28px 24px; background: #fff; border-right: 1px solid #ebeef5; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.module-title { font-size: 20px; font-weight: 700; color: #303133; margin: 0; }
.mode-tabs { display: flex; margin-bottom: 20px; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; }
.mode-tab { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #f5f7fa; color: #909399; transition: all .2s; user-select: none; }
.mode-tab.active { background: #409eff; color: #fff; font-weight: 500; }
.form-group { margin-bottom: 18px; }
.form-label { display: block; font-size: 14px; font-weight: 500; color: #303133; margin-bottom: 8px; }
.required { color: #f56c6c; }
.upload-xmind { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 36px 20px; border: 2px dashed #dcdfe6; border-radius: 10px; background: #fafafa; cursor: pointer; transition: all .2s; min-height: 160px; }
.upload-xmind:hover { border-color: #409eff; background: #f0f7ff; }
.upload-text { font-size: 14px; color: #606266; margin: 12px 0 4px; }
.upload-hint { font-size: 12px; color: #c0c4cc; margin: 0; }
.btn-generate { width: 100%; height: 46px; font-size: 15px; border-radius: 10px; background: #c8c9cc !important; border-color: #c8c9cc !important; color: #fff !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #409eff !important; border-color: #409eff !important; }
:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }
:deep(.el-input .el-input__wrapper) { border-radius: 8px; }
:deep(.el-select .el-input__wrapper) { border-radius: 8px; }
.preview-panel { flex: 1; background: #f5f7fa; padding: 40px; overflow-y: auto; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; min-height: 400px; }
.placeholder-title { font-size: 16px; color: #909399; margin: 20px 0 8px; }
.placeholder-desc { font-size: 13px; color: #c0c4cc; margin: 0; max-width: 320px; text-align: center; line-height: 1.7; }
.result-content { line-height: 1.8; color: #303133; font-size: 14px; }
</style>
