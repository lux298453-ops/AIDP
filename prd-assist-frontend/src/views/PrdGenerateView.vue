<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTaskById, getTaskSseUrl } from '@/api/task'
import client from '@/api/client'

const activeTab = ref<'direct' | 'xmind'>('direct')
const router = useRouter()
const funcName = ref('')
const description = ref('')
const template = ref('STANDARD')
const detailLevel = ref('CONCISE')

const xmindInput = ref<HTMLInputElement | null>(null)
const xmindFile = ref<File | null>(null)

// 自定义模板
const templateInput = ref<HTMLInputElement | null>(null)
const customTemplateFile = ref<File | null>(null)
const isCustomTemplate = computed(() => template.value === 'CUSTOM')
const templateDragOver = ref(false)

// 切换回标准模板时清空已上传的自定义模板
watch(template, (val) => {
  if (val !== 'CUSTOM') {
    customTemplateFile.value = null
    if (templateInput.value) templateInput.value.value = ''
  }
})

function triggerXmindUpload() { xmindInput.value?.click() }
function onXmindFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    const f = input.files[0]
    if (!f.name.toLowerCase().endsWith('.xmind')) { ElMessage.warning('仅支持 .xmind 文件'); return }
    xmindFile.value = f; ElMessage.success(`已选择: ${f.name}`)
  }
}

function triggerTemplateUpload() { templateInput.value?.click() }
function onTemplateFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    acceptTemplateFile(input.files[0])
  }
}
function acceptTemplateFile(f: File) {
  if (!f.name.toLowerCase().endsWith('.docx')) {
    ElMessage.warning('自定义模板仅支持 .docx 文件')
    return
  }
  if (f.size > 20 * 1024 * 1024) {
    ElMessage.warning('模板文件不能超过 20MB')
    return
  }
  customTemplateFile.value = f
  ElMessage.success(`已选择模板: ${f.name}`)
}
function clearTemplateFile(e?: Event) {
  e?.stopPropagation()
  customTemplateFile.value = null
  if (templateInput.value) templateInput.value.value = ''
}
function onTemplateDragOver(e: DragEvent) {
  e.preventDefault()
  templateDragOver.value = true
}
function onTemplateDragLeave(e: DragEvent) {
  e.preventDefault()
  templateDragOver.value = false
}
function onTemplateDrop(e: DragEvent) {
  e.preventDefault()
  templateDragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) acceptTemplateFile(f)
}

const loading = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const liveMessages = ref<string[]>([])
const result = ref('')
let taskId: number | null = null

const canGenerate = computed(() => {
  if (template.value === 'CUSTOM' && !customTemplateFile.value) return false
  if (activeTab.value === 'direct') return !!funcName.value.trim() && !!description.value.trim()
  return xmindFile.value !== null
})

async function handleGenerate() {
  loading.value = true
  result.value = ''
  progress.value = 0
  progressMsg.value = '正在提交生成任务...'
  liveMessages.value = ['正在提交生成任务...']
  taskId = null
  try {
    if (template.value === 'CUSTOM' && !customTemplateFile.value) {
      ElMessage.warning('请上传自定义模板文件'); loading.value = false; return
    }

    if (activeTab.value === 'direct') {
      if (!funcName.value.trim()) { ElMessage.warning('请输入功能名称'); loading.value = false; return }
      if (!description.value.trim()) { ElMessage.warning('请输入需求描述'); loading.value = false; return }

      if (template.value === 'CUSTOM' && customTemplateFile.value) {
        // 自定义模板：走 multipart
        const fd = new FormData()
        fd.append('featureName', funcName.value)
        fd.append('description', description.value)
        fd.append('template', 'CUSTOM')
        fd.append('detailLevel', detailLevel.value)
        fd.append('customTemplateFile', customTemplateFile.value)
        const res = await client.post('/prd/generate', fd, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
        taskId = res.data.data.taskId
      } else {
        // 标准模板：JSON
        const res = await client.post('/prd/generate', {
          featureName: funcName.value,
          description: description.value,
          template: template.value,
          detailLevel: detailLevel.value,
        })
        taskId = res.data.data.taskId
      }
    } else {
      if (!xmindFile.value) { ElMessage.warning('请上传 XMind 文件'); loading.value = false; return }
      const fd = new FormData()
      fd.append('file', xmindFile.value)
      fd.append('template', template.value)
      fd.append('detailLevel', detailLevel.value)
      if (template.value === 'CUSTOM' && customTemplateFile.value) {
        fd.append('customTemplateFile', customTemplateFile.value)
      }
      const res = await client.post('/prd/generate/xmind', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      taskId = res.data.data.taskId
    }
    startSse()
  } catch { loading.value = false }
}

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 生成服务...')
  const eventSource = new EventSource(getTaskSseUrl(taskId))
  eventSource.addEventListener('progress', async (event) => {
    try {
      const data = JSON.parse((event as MessageEvent).data)
      progress.value = data.progress
      progressMsg.value = data.message || progressMsg.value
      appendLiveMessage(data.message)
      if (data.progress <= 0 && data.message && data.message.includes('失败')) {
        eventSource.close(); loading.value = false
        ElMessage.error(data.message)
        return
      }
      if (data.progress >= 100) {
        eventSource.close(); loading.value = false
        appendLiveMessage('生成完成，正在打开 PRD 文档...')
        ElMessage.success('PRD 生成完成')
        const r = await getTaskById(taskId!)
        const refId = r.data.data.resultRefId
        if (refId) await router.push(`/prd/${refId}`)
      }
    } catch { /* ignore */ }
  })
  eventSource.onerror = async () => {
    eventSource.close()
    try {
      const r = await getTaskById(taskId!)
      if (r.data.data.status === 'SUCCESS' && r.data.data.resultRefId) {
        loading.value = false; await router.push(`/prd/${r.data.data.resultRefId}`)
      } else if (r.data.data.status === 'FAILED') {
        loading.value = false; ElMessage.error(r.data.data.errorMessage || '生成失败')
      } else {
        loading.value = false; ElMessage.warning('进度连接已断开，请在我的文档中查看任务结果')
      }
    } catch { loading.value = false }
  }
}

function appendLiveMessage(message?: string) {
  const text = (message || '').trim()
  if (!text) return
  if (liveMessages.value[liveMessages.value.length - 1] === text) return
  liveMessages.value = [...liveMessages.value.slice(-5), text]
}

function parseAndFormat(raw: any): string {
  if (raw && typeof raw === 'object') return fmt(raw)
  if (typeof raw === 'string') {
    let obj: any = null
    try { obj = JSON.parse(raw) } catch {}
    if (!obj) { try { obj = JSON.parse(raw.replace(/```json\n?/g,'').replace(/```/g,'').trim()) } catch {} }
    if (!obj) { const a=raw.indexOf('{'), b=raw.lastIndexOf('}'); if(a>=0&&b>a) try { obj=JSON.parse(raw.substring(a,b+1)) } catch {} }
    if (obj) return fmt(obj)
    return raw.replace(/\\n/g,'<br>').replace(/\\"/g,'"')
  }
  return String(raw)
}

function fmt(j: any): string {
  let t = ''
  if (j.title) t += `<h3>${j.title}</h3>`
  if (j.summary) t += `<p><em>${j.summary}</em></p>`
  if (j.chapters) for (const c of j.chapters) t += `<h4>${c.title||''}</h4><p>${String(c.content||'').replace(/\n/g,'<br>')}</p>`
  return t || JSON.stringify(j)
}
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">PRD生成</h2>
          <el-icon size="18" color="rgba(38,37,30,0.4)"><Clock /></el-icon>
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
            <el-input v-model="description" type="textarea" :rows="8" placeholder="请描述您的产品需求，最多50000字" maxlength="50000" show-word-limit />
          </div>
        </template>

        <template v-if="activeTab === 'xmind'">
          <div class="form-group">
            <label class="form-label">上传XMind文件</label>
            <input ref="xmindInput" type="file" accept=".xmind" style="display:none" @change="onXmindFileSelected" />
            <div class="upload-zone" @click="triggerXmindUpload">
              <el-icon size="40" :color="xmindFile ? '#1f8a65' : 'rgba(38,37,30,0.2)'"><UploadFilled /></el-icon>
              <p class="upload-text">{{ xmindFile ? xmindFile.name : '点击上传 .xmind 文件' }}</p>
              <p class="upload-hint">{{ xmindFile ? `${(xmindFile.size / 1024).toFixed(1)} KB` : '或将文件拖拽到此处' }}</p>
            </div>
          </div>
        </template>

        <div class="form-group">
          <label class="form-label">模板选择</label>
          <el-select v-model="template" style="width:100%">
            <el-option label="标准模板" value="STANDARD" />
            <el-option label="选择其他模板" value="CUSTOM" />
          </el-select>
        </div>

        <!-- 自定义模板上传区：仅在选择 CUSTOM 时展示 -->
        <div v-if="isCustomTemplate" class="form-group">
          <label class="form-label">
            自定义模板 <span class="required">*</span>
          </label>
          <input
            ref="templateInput"
            type="file"
            accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            style="display:none"
            @change="onTemplateFileSelected"
          />

          <!-- 已上传：展示文件名 + 删除 -->
          <div v-if="customTemplateFile" class="upload-zone upload-zone--done">
            <el-icon size="36" color="#1f8a65"><Document /></el-icon>
            <p class="upload-text">{{ customTemplateFile.name }}</p>
            <p class="upload-hint">{{ (customTemplateFile.size / 1024).toFixed(1) }} KB · AI 将按此模板结构生成</p>
            <el-button
              class="btn-remove-file"
              size="small"
              text
              type="danger"
              @click="clearTemplateFile"
            >
              删除
            </el-button>
          </div>

          <!-- 未上传：虚线框 + 拖拽 -->
          <div
            v-else
            class="upload-zone"
            :class="{ 'upload-zone--drag': templateDragOver }"
            @click="triggerTemplateUpload"
            @dragover="onTemplateDragOver"
            @dragleave="onTemplateDragLeave"
            @drop="onTemplateDrop"
          >
            <el-icon size="40" color="rgba(38,37,30,0.2)"><UploadFilled /></el-icon>
            <p class="upload-text">点击上传自定义模板文件</p>
            <p class="upload-hint">上传你的模板文件，AI 将按照该模板结构生成PRD</p>
            <p class="upload-hint" style="margin-top:6px">支持 .docx · 可拖拽到此处</p>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">详略程度</label>
          <el-radio-group v-model="detailLevel" style="display:flex;gap:12px">
            <el-radio value="CONCISE">简洁</el-radio>
            <el-radio value="DETAILED">详细</el-radio>
          </el-radio-group>
        </div>

        <el-button class="btn-generate" :loading="loading" :disabled="!canGenerate" @click="handleGenerate">一键生成</el-button>
      </div>

      <div class="preview-panel">
        <template v-if="result">
          <div class="result-content" v-html="result" />
        </template>
        <template v-else-if="loading">
          <div class="preview-placeholder generating-state">
            <el-icon class="loading-icon" size="54" color="#26251e"><Loading /></el-icon>
            <p class="generating-title">正在生成...</p>
            <p class="generating-desc">{{ progressMsg || 'AI 正在生成 PRD，请稍候' }}</p>
            <div class="live-output">
              <div v-for="(msg, index) in liveMessages" :key="index" class="live-line">
                {{ msg }}
              </div>
            </div>
            <p class="placeholder-title">AI 正在生成 PRD...</p>
            <p class="placeholder-desc">请稍候，DeepSeek 正在为您撰写专业文档</p>
          </div>
        </template>
        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="rgba(38,37,30,0.15)"><Document /></el-icon>
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
.workspace { display: flex; height: 100%; border-radius: 10px; overflow: hidden; box-shadow: rgba(38, 37, 30, 0.1) 0px 0px 0px 1px; }
.config-panel { width: 420px; flex-shrink: 0; padding: 28px 24px; background: #f2f1ed; border-right: 1px solid rgba(38, 37, 30, 0.1); overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.module-title { font-size: 22px; font-weight: 400; color: #26251e; margin: 0; letter-spacing: -0.11px; }
.mode-tabs { display: flex; margin-bottom: 20px; border-radius: 8px; overflow: hidden; gap: 4px; }
.mode-tab { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #e6e5e0; color: rgba(38, 37, 30, 0.55); transition: all .15s; user-select: none; border-radius: 8px; font-weight: 500; }
.mode-tab.active { background: #26251e; color: #f2f1ed; }
.mode-tab:hover:not(.active) { color: #cf2d56; }
.form-group { margin-bottom: 18px; }
.form-label { display: block; font-size: 14px; font-weight: 500; color: #26251e; margin-bottom: 8px; }
.required { color: #cf2d56; }

/* 统一上传区域（XMind + 自定义模板共用） */
.upload-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 36px 20px;
  border: 2px dashed rgba(38, 37, 30, 0.2);
  border-radius: 10px;
  background: #f7f7f4;
  cursor: pointer;
  transition: all .2s;
  min-height: 160px;
  position: relative;
}
.upload-zone:hover { border-color: #f54e00; background: rgba(245, 78, 0, 0.03); }
.upload-zone--drag { border-color: #f54e00; background: rgba(245, 78, 0, 0.06); }
.upload-zone--done {
  min-height: 140px;
  border-style: solid;
  border-color: rgba(31, 138, 101, 0.35);
  background: rgba(31, 138, 101, 0.04);
  cursor: default;
}
.upload-zone--done:hover { border-color: rgba(31, 138, 101, 0.5); background: rgba(31, 138, 101, 0.06); }
.upload-text { font-size: 14px; color: rgba(38, 37, 30, 0.75); margin: 12px 0 4px; word-break: break-all; text-align: center; padding: 0 8px; }
.upload-hint { font-size: 12px; color: rgba(38, 37, 30, 0.4); margin: 0; text-align: center; line-height: 1.5; }
.btn-remove-file { margin-top: 10px; }

.btn-generate { width: 100%; height: 46px; font-size: 15px; font-weight: 400; border-radius: 8px; background: #e6e5e0 !important; border-color: transparent !important; color: rgba(38, 37, 30, 0.4) !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #26251e !important; border-color: #26251e !important; color: #f2f1ed !important; }
.btn-generate:not(:disabled):hover { opacity: 0.85; }
:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }
:deep(.el-input .el-input__wrapper) { border-radius: 8px; }
:deep(.el-select .el-input__wrapper) { border-radius: 8px; }
.preview-panel { flex: 1; background: #f7f7f4; padding: 40px; overflow-y: auto; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; min-height: 400px; }
.placeholder-title { font-size: 16px; color: rgba(38, 37, 30, 0.55); margin: 20px 0 8px; font-weight: 400; }
.placeholder-desc { font-size: 13px; color: rgba(38, 37, 30, 0.4); margin: 0; max-width: 320px; text-align: center; line-height: 1.7; }
.loading-icon { animation: spin 1s linear infinite; }
.generating-state > .placeholder-title,
.generating-state > .placeholder-desc { display: none; }
.generating-title { font-size: 17px; color: #26251e; margin: 18px 0 8px; font-weight: 500; }
.generating-desc { font-size: 13px; color: rgba(38, 37, 30, 0.58); margin: 0; text-align: center; line-height: 1.7; }
.live-output {
  width: min(520px, 82%);
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(38, 37, 30, 0.08);
}
.live-line { font-size: 13px; line-height: 1.7; color: rgba(38, 37, 30, 0.66); }
.live-line + .live-line { margin-top: 6px; }
@keyframes spin { to { transform: rotate(360deg); } }
.result-content { line-height: 1.8; color: #26251e; font-size: 14px; }
</style>
