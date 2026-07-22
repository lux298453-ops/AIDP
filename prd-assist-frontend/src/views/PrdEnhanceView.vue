<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { createTask, getTaskSseUrl, getTaskById } from '@/api/task'
import client from '@/api/client'

// ==================== 输入来源 ====================
const inputMode = ref<'paste' | 'existing'>('paste')
const prdContent = ref('')
const prdDocumentId = ref<number | null>(null)

// ==================== Word 上传 ====================
const wordInput = ref<HTMLInputElement | null>(null)
const wordFile = ref<File | null>(null)

function triggerWordUpload() { wordInput.value?.click() }
function onWordFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    const f = input.files[0]
    if (!f.name.toLowerCase().endsWith('.doc') && !f.name.toLowerCase().endsWith('.docx')) {
      ElMessage.warning('仅支持 .doc / .docx 文件'); return
    }
    wordFile.value = f; ElMessage.success(`已选择: ${f.name}`)
  }
}

// ==================== 内容类型（全部默认选中） ====================
const contentTypes = ref([
  { key: 'structure', label: '页面结构图', icon: 'Grid', active: true },
  { key: 'flow', label: '流程图', icon: 'Share', active: true },
  { key: 'data', label: '数据字段', icon: 'Collection', active: true },
  { key: 'testcase', label: '测试用例', icon: 'List', active: true },
])

function toggleType(item: { key: string; active: boolean }) { item.active = !item.active }

// ==================== 自定义指令 ====================
const instruction = ref('')

// ==================== 状态 ====================
const loading = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const result = ref('')
let taskId: number | null = null

const canSubmit = computed(() => {
  const hasTypes = contentTypes.value.some(t => t.active)
  return inputMode.value === 'existing' || (prdContent.value.trim().length > 0 && hasTypes) || (wordFile.value && hasTypes)
})

// ==================== 提交 ====================
async function handleEnhance() {
  const selectedTypes = contentTypes.value.filter(t => t.active).map(t => t.key)
  if (selectedTypes.length === 0) { ElMessage.warning('请至少选择一种内容类型'); return }
  if (inputMode.value === 'paste' && !prdContent.value.trim()) { ElMessage.warning('请输入 PRD 内容'); return }

  loading.value = true; result.value = ''; progress.value = 0; progressMsg.value = ''

  try {
    if (wordFile.value) {
      // Word 上传模式
      const fd = new FormData(); fd.append('file', wordFile.value)
      if (prdContent.value) fd.append('prdContent', prdContent.value)
      if (prdDocumentId.value) fd.append('prdDocumentId', String(prdDocumentId.value))
      fd.append('contentTypes', selectedTypes.join(','))
      if (instruction.value) fd.append('instruction', instruction.value)

      const res = await client.post('/prd/enhance/word', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
      taskId = res.data.data.taskId
    } else {
      // 纯文本模式
      const res = await client.post('/prd/enhance', {
        prdContent: prdContent.value, prdDocumentId: prdDocumentId.value,
        contentTypes: selectedTypes, instruction: instruction.value,
      })
      taskId = res.data.data.taskId
    }
    startSse()
  } catch { loading.value = false }
}

function startSse() {
  if (!taskId) return
  const es = new EventSource(getTaskSseUrl(taskId))
  es.addEventListener('progress', (e) => {
    const d = JSON.parse(e.data); progress.value = d.progress; progressMsg.value = d.message
    if (d.progress >= 100) { es.close(); loading.value = false; ElMessage.success('增强完成'); pollResult() }
  })
  es.onerror = () => { es.close(); loading.value = false }
}

async function pollResult() {
  if (!taskId) return
  const t = setInterval(async () => {
    const r = await getTaskById(taskId!)
    if (r.data.data.status === 'SUCCESS') { result.value = r.data.data.outputData || ''; clearInterval(t) }
    if (r.data.data.status === 'FAILED') { ElMessage.error(r.data.data.errorMessage || '失败'); clearInterval(t) }
  }, 2000)
}
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧面板 ====== -->
      <div class="config-panel">
        <div class="panel-header">
          <h2 class="module-title">PRD增强</h2>
          <el-icon size="18" color="#909399"><Clock /></el-icon>
        </div>

        <!-- 输入模式 -->
        <div class="form-group">
          <label class="form-label">PRD 来源</label>
          <div class="mode-tabs">
            <div class="mode-tab" :class="{ active: inputMode === 'paste' }" @click="inputMode = 'paste'">直接粘贴</div>
            <div class="mode-tab" :class="{ active: inputMode === 'existing' }" @click="inputMode = 'existing'">选择已有PRD</div>
          </div>
        </div>

        <!-- 粘贴模式 -->
        <div v-if="inputMode === 'paste'" class="form-group">
          <label class="form-label">PRD 内容</label>
          <el-input v-model="prdContent" type="textarea" :rows="8" placeholder="请粘贴已有的 PRD 文档内容..." maxlength="5000" show-word-limit />
        </div>

        <!-- 内容类型 -->
        <div class="form-group">
          <label class="form-label">内容包含</label>
          <div class="content-cards">
            <div v-for="item in contentTypes" :key="item.key" class="content-card" :class="{ active: item.active }" @click="toggleType(item)">
              <el-icon size="18"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </div>
          </div>
        </div>

        <!-- Word 素材上传 -->
        <div class="form-group">
          <label class="form-label">PRD素材 示例</label>
          <input ref="wordInput" type="file" accept=".doc,.docx" style="display:none" @change="onWordFileSelected" />
          <div class="upload-dashed" @click="triggerWordUpload">
            <el-icon size="22" :color="wordFile ? '#67c23a' : '#909399'"><Upload /></el-icon>
            <span>{{ wordFile ? wordFile.name : '点击上传word素材（doc格式）' }}</span>
          </div>
        </div>

        <!-- 自定义指令 -->
        <div class="form-group">
          <label class="form-label">自定义指令（可选）</label>
          <el-input v-model="instruction" type="textarea" :rows="2" placeholder="例如：重点补充移动端的适配方案" maxlength="500" />
        </div>

        <!-- 一键生成 -->
        <el-button class="btn-generate" :loading="loading" :disabled="!canSubmit" @click="handleEnhance">一键生成</el-button>
        <el-progress v-if="loading" :percentage="progress" :stroke-width="6" style="margin-top:12px" />
        <p v-if="progressMsg" style="color:#909399;font-size:13px;margin-top:6px">{{ progressMsg }}</p>
      </div>

      <!-- ====== 右侧预览区 ====== -->
      <div class="preview-panel">
        <template v-if="result">
          <div class="result-content" v-html="result.replace(/\n/g, '<br>')" />
        </template>
        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="#dcdfe6"><Document /></el-icon>
            <p class="placeholder-title">增强结果即将呈现</p>
            <p class="placeholder-desc">在左侧粘贴 PRD 并选择要补充的内容类型，点击一键生成</p>
          </div>
        </template>
        <div v-if="loading" class="preview-placeholder">
          <el-skeleton animated style="width:80%;max-width:500px">
            <template #template>
              <div style="display:flex;flex-direction:column;gap:16px;align-items:center">
                <el-skeleton-item variant="rect" style="width:100%;height:200px;border-radius:10px" />
                <el-skeleton-item variant="text" style="width:60%" />
                <el-skeleton-item variant="text" style="width:40%" />
              </div>
            </template>
          </el-skeleton>
          <p style="color:#909399;font-size:14px;margin-top:20px">{{ progressMsg || 'AI 正在增强 PRD，请稍候…' }}</p>
        </div>
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
.form-group { margin-bottom: 18px; }
.form-label { display: block; font-size: 14px; font-weight: 500; color: #303133; margin-bottom: 8px; }

/* 模式切换 */
.mode-tabs { display: flex; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; }
.mode-tab { flex: 1; text-align: center; padding: 10px 0; font-size: 13px; cursor: pointer; background: #f5f7fa; color: #909399; transition: all .2s; user-select: none; }
.mode-tab.active { background: #409eff; color: #fff; font-weight: 500; }

/* 内容卡片 */
.content-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.content-card { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 16px 10px; border: 1.5px solid #409eff; border-radius: 10px; cursor: pointer; background: #ecf5ff; color: #409eff; font-size: 13px; transition: all .2s; }
.content-card:not(.active) { border-color: #ebeef5; background: #fafafa; color: #909399; }

/* 虚线框上传 */
.upload-dashed { display: flex; align-items: center; gap: 10px; padding: 16px 18px; border: 1.5px dashed #dcdfe6; border-radius: 10px; color: #909399; font-size: 14px; cursor: pointer; background: #fafafa; transition: border-color .2s; }
.upload-dashed:hover { border-color: #409eff; }

/* 灰色按钮 */
.btn-generate { width: 100%; height: 46px; font-size: 15px; border-radius: 10px; background: #c8c9cc !important; border-color: #c8c9cc !important; color: #fff !important; margin-top: 8px; }
.btn-generate:not(:disabled) { background: #409eff !important; border-color: #409eff !important; }

:deep(.el-textarea .el-textarea__inner) { border-radius: 8px; font-size: 14px; }

/* 右侧预览 */
.preview-panel { flex: 1; background: #f5f7fa; padding: 40px; overflow-y: auto; }
.preview-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; min-height: 400px; }
.placeholder-title { font-size: 16px; color: #909399; margin: 20px 0 8px; }
.placeholder-desc { font-size: 13px; color: #c0c4cc; margin: 0; max-width: 320px; text-align: center; line-height: 1.7; }
.result-content { line-height: 1.8; color: #303133; font-size: 14px; }
</style>
