<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import ThinkingStatus from '@/components/common/ThinkingStatus.vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Document,
  UploadFilled,
  Clock,
  MagicStick,
  Delete,
} from '@element-plus/icons-vue'
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
const liveContent = ref('')
const result = ref('')
let taskId: number | null = null

const canGenerate = computed(() => {
  if (template.value === 'CUSTOM' && !customTemplateFile.value) return false
  if (activeTab.value === 'direct') return !!funcName.value.trim() && !!description.value.trim()
  return xmindFile.value !== null
})

// 快捷示例填充，提升初次使用信任感与体验
const samplePrompts = [
  {
    title: '电商优惠券结算逻辑',
    name: '全平台满减与品类优惠券结算中心',
    desc: '设计电商下单结算页的优惠券自动推荐与多券叠加抵扣机制。需包含满减券、无门槛券、商品券优先级规则，用户手动勾选交互，以及退款时优惠券的原路退回/失效分支场景。',
  },
  {
    title: 'B 端角色权限审批流',
    name: '企业级组织架构与角色权限审批系统',
    desc: '面向中大型企业的多级部门角色分配、RBAC 权限模型及敏感操作多级审批流。支持按部门动态抄送、会签或或签逻辑，并具备操作审计日志可追溯。',
  },
  {
    title: '用户成长会员中心',
    name: 'C 端成长值与会员权益中心',
    desc: '打造多等级会员权益体系，包含签到、任务积分兑换、月度等级保级与降级策略，并在个人中心提供沉浸式等级晋升动画与权益卡片。',
  },
]

function applySample(sample: { name: string; desc: string }) {
  activeTab.value = 'direct'
  funcName.value = sample.name
  description.value = sample.desc
}

async function handleGenerate() {
  loading.value = true
  result.value = ''
  liveContent.value = ''
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

let activeEventSource: EventSource | null = null

onBeforeUnmount(() => {
  activeEventSource?.close()
  activeEventSource = null
})

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接 AI 生成服务...')
  activeEventSource?.close()
  const eventSource = new EventSource(getTaskSseUrl(taskId))
  activeEventSource = eventSource
  eventSource.addEventListener('content', (event) => {
    try {
      const data = JSON.parse((event as MessageEvent).data)
      if (data.snapshot) liveContent.value = data.delta || ''
      else liveContent.value += data.delta || ''
      result.value = formatLiveContent(liveContent.value)
    } catch { /* ignore */ }
  })
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

function formatLiveContent(text: string) {
  return escapeHtml(text).replace(/\n/g, '<br>')
}

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <!-- ====== 左侧配置面板 (统一 380px) ====== -->
      <aside class="config-panel">
        <div class="panel-header">
          <div>
            <h2 class="module-title">PRD 生成</h2>
            <p class="module-subtitle">结构化产品需求与用例撰写</p>
          </div>
          <div class="panel-badge" title="需求规格撰写">
            <el-icon :size="15"><Document /></el-icon>
          </div>
        </div>

        <!-- 模式切换：胶囊分段器 -->
        <div class="mode-tabs">
          <button
            type="button"
            class="mode-tab"
            :class="{ active: activeTab === 'direct' }"
            @click="activeTab = 'direct'"
          >
            直接描述需求
          </button>
          <button
            type="button"
            class="mode-tab"
            :class="{ active: activeTab === 'xmind' }"
            @click="activeTab = 'xmind'"
          >
            导入 XMind 脑图
          </button>
        </div>

        <!-- 直接生成表单 -->
        <template v-if="activeTab === 'direct'">
          <div class="form-group">
            <label class="form-label">
              功能名称 <span class="required">*</span>
            </label>
            <el-input
              v-model="funcName"
              placeholder="例如：电商优惠券叠加结算模块"
              maxlength="50"
              show-word-limit
            />
          </div>

          <div class="form-group">
            <label class="form-label">
              需求描述 <span class="required">*</span>
            </label>
            <el-input
              v-model="description"
              type="textarea"
              :rows="9"
              placeholder="详细描述业务目标、核心场景、交互链路和异常情况..."
              maxlength="50000"
              show-word-limit
            />
          </div>
        </template>

        <!-- XMind 模式 -->
        <template v-if="activeTab === 'xmind'">
          <div class="form-group">
            <label class="form-label">上传 XMind 文件 <span class="required">*</span></label>
            <input ref="xmindInput" type="file" accept=".xmind" style="display:none" @change="onXmindFileSelected" />
            <div class="upload-zone" :class="{ 'upload-zone--done': !!xmindFile }" @click="triggerXmindUpload">
              <el-icon :size="32" :color="xmindFile ? '#059669' : '#94a3b8'"><UploadFilled /></el-icon>
              <p class="upload-text">{{ xmindFile ? xmindFile.name : '点击上传 .xmind 脑图文件' }}</p>
              <p class="upload-hint">{{ xmindFile ? `${(xmindFile.size / 1024).toFixed(1)} KB · 点击可替换` : 'AI 将解析节点层级并生成章节结构' }}</p>
            </div>
          </div>
        </template>

        <!-- 模板选择 -->
        <div class="form-group">
          <label class="form-label">文档模板规范</label>
          <el-select v-model="template" style="width:100%">
            <el-option label="标准大厂 PRD 模板" value="STANDARD" />
            <el-option label="自定义 Word 模板 (.docx)" value="CUSTOM" />
          </el-select>
        </div>

        <!-- 自定义模板上传区 -->
        <div v-if="isCustomTemplate" class="form-group">
          <label class="form-label">
            自定义模板文件 <span class="required">*</span>
          </label>
          <input
            ref="templateInput"
            type="file"
            accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            style="display:none"
            @change="onTemplateFileSelected"
          />

          <!-- 已上传 -->
          <div v-if="customTemplateFile" class="upload-zone upload-zone--done">
            <el-icon :size="32" color="#059669"><Document /></el-icon>
            <p class="upload-text">{{ customTemplateFile.name }}</p>
            <p class="upload-hint">{{ (customTemplateFile.size / 1024).toFixed(1) }} KB · AI 将严格遵循此模板排版</p>
            <button type="button" class="btn-remove-link" @click="clearTemplateFile">
              <el-icon><Delete /></el-icon>
              <span>移除文件</span>
            </button>
          </div>

          <!-- 未上传 -->
          <div
            v-else
            class="upload-zone"
            :class="{ 'upload-zone--drag': templateDragOver }"
            @click="triggerTemplateUpload"
            @dragover="onTemplateDragOver"
            @dragleave="onTemplateDragLeave"
            @drop="onTemplateDrop"
          >
            <el-icon :size="32" color="#94a3b8"><UploadFilled /></el-icon>
            <p class="upload-text">点击上传 .docx 模板文件</p>
            <p class="upload-hint">支持拖拽到此处，最大不超过 20MB</p>
          </div>
        </div>

        <!-- 详略程度 -->
        <div class="form-group">
          <label class="form-label">输出详略程度</label>
          <el-radio-group v-model="detailLevel" class="w-full">
            <el-radio-button value="CONCISE">简洁概括</el-radio-button>
            <el-radio-button value="DETAILED">详尽推导</el-radio-button>
          </el-radio-group>
        </div>

        <!-- 生成主按钮 -->
        <div class="generate-btn-wrap">
          <button
            type="button"
            class="btn-generate"
            :disabled="!canGenerate || loading"
            @click="handleGenerate"
          >
            <el-icon v-if="!loading" :size="16"><MagicStick /></el-icon>
            <span>{{ loading ? 'AI 正在分析并生成 PRD...' : '一键生成 PRD' }}</span>
          </button>
        </div>
      </aside>

      <!-- ====== 右侧主舞台区 ====== -->
      <main class="preview-panel">
        <!-- 生成中状态 -->
        <template v-if="loading">
          <div class="generating-container">
            <div class="status-box">
              <ThinkingStatus :steps="liveMessages" />
            </div>
            <div class="generating-meta">
              <span class="pulse-indicator" />
              <span>正在结构化输出文档内容，请稍候...</span>
            </div>
          </div>
        </template>

        <!-- 结果展示 -->
        <template v-else-if="result">
          <div class="result-card">
            <div class="result-header">
              <span class="result-badge">实时草稿预览</span>
              <span class="result-hint">完成生成后将自动跳转至结构化编辑器</span>
            </div>
            <div class="result-content" v-html="result" />
          </div>
        </template>

        <!-- 初始空状态（专业引导态，含可点击的样例标签） -->
        <template v-else>
          <div class="onboarding-container">
            <div class="empty-icon-wrap">
              <el-icon :size="32" color="#0f172a"><Document /></el-icon>
            </div>
            <h3 class="empty-title">结构化 PRD 文档生成</h3>
            <p class="empty-desc">
              在左侧输入功能想法或上传脑图，AI 将按工业级规范输出功能用例、业务流程与异常分支分支。
            </p>

            <!-- 推荐示例 Chips -->
            <div class="sample-section">
              <span class="sample-label">不知道怎么写？点击填入推荐示例：</span>
              <div class="sample-chips">
                <button
                  v-for="item in samplePrompts"
                  :key="item.title"
                  type="button"
                  class="sample-chip"
                  @click="applySample(item)"
                >
                  <el-icon :size="13"><Sparkles /></el-icon>
                  <span>{{ item.title }}</span>
                </button>
              </div>
            </div>

            <!-- 特性说明卡片 -->
            <div class="feature-strip">
              <div class="feature-item">
                <span class="f-dot" />
                <span>支持 XMind 脑图逆向推导 PRD</span>
              </div>
              <div class="feature-item">
                <span class="f-dot" />
                <span>一键导出 Word 标准文档并带图表</span>
              </div>
              <div class="feature-item">
                <span class="f-dot" />
                <span>无缝下游联动原型推导与质量审查</span>
              </div>
            </div>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.page-container {
  padding: 24px 32px;
  height: 100%;
  box-sizing: border-box;
}
.workspace {
  display: flex;
  height: 100%;
  background: #ffffff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08), 0 2px 4px rgba(15, 23, 42, 0.03);
}

/* 侧栏 */
.config-panel {
  width: 380px;
  flex-shrink: 0;
  padding: 22px 20px;
  background: #ffffff;
  border-right: 1px solid #f1f5f9;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.module-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  margin: 0 0 2px;
  letter-spacing: -0.02em;
}
.module-subtitle {
  font-size: 12px;
  color: #94a3b8;
  margin: 0;
}
.panel-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background: #f8fafc;
  color: #2563eb;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
  transition: all 0.15s ease;
}

/* 模式分段选择器 */
.mode-tabs {
  display: flex;
  background: #f1f5f9;
  padding: 3px;
  border: 1px solid rgba(226, 232, 240, 0.8);
  border-radius: 8px;
  gap: 2px;
}
.mode-tab {
  flex: 1;
  text-align: center;
  padding: 6px 0;
  font-size: 12.5px;
  cursor: pointer;
  background: transparent;
  color: #64748b;
  border: none;
  border-radius: 6px;
  font-weight: 500;
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}
.mode-tab:hover:not(.active) {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.5);
}
.mode-tab.active {
  background: #ffffff;
  color: #0f172a;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08), 0 0 0 1px rgba(15, 23, 42, 0.04);
}

/* 表单组 */
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-label {
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}
.required {
  color: #dc2626;
}

/* 统一上传区 */
.upload-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  border: 1.5px dashed #cbd5e1;
  border-radius: 10px;
  background: #f8fafc;
  cursor: pointer;
  transition: all 0.15s ease;
  min-height: 120px;
}
.upload-zone:hover {
  border-color: #2563eb;
  background: #eff6ff;
}
.upload-zone--drag {
  border-color: #f97316;
  background: #fff7ed;
}
.upload-zone--done {
  border-style: solid;
  border-color: #a7f3d0;
  background: #f0fdf4;
  cursor: default;
}
.upload-text {
  font-size: 13px;
  font-weight: 500;
  color: #0f172a;
  margin: 8px 0 2px;
  text-align: center;
  word-break: break-all;
}
.upload-hint {
  font-size: 11.5px;
  color: #94a3b8;
  margin: 0;
  text-align: center;
}
.btn-remove-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  background: transparent;
  border: none;
  color: #dc2626;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
}
.btn-remove-link:hover {
  background: #fef2f2;
}

/* 生成按钮 */
.generate-btn-wrap {
  margin-top: auto;
  padding-top: 12px;
}
.btn-generate {
  width: 100%;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 7px;
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%);
  color: #ffffff;
  border: 1px solid #1d4ed8;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  cursor: pointer;
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.25), 0 1px 2px 0 rgba(15, 23, 42, 0.08);
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}
.btn-generate:hover:not(:disabled) {
  background: linear-gradient(180deg, #2563eb 0%, #1d4ed8 100%);
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.2), 0 2px 5px 0 rgba(37, 99, 235, 0.25);
}
.btn-generate:active:not(:disabled) {
  transform: translateY(1px);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.2);
}
.btn-generate:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
  background: #94a3b8;
  border-color: #94a3b8;
}

/* 右侧主面板 */
.preview-panel {
  flex: 1;
  background: #f8fafc;
  padding: 32px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* 空状态 (Onboarding) */
.onboarding-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: auto;
  max-width: 580px;
  text-align: center;
  padding: 24px 0;
}
.empty-icon-wrap {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.04);
}
.empty-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}
.empty-desc {
  margin: 0 0 28px;
  font-size: 13.5px;
  color: #64748b;
  line-height: 1.6;
}
.sample-section {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  margin-bottom: 28px;
}
.sample-label {
  font-size: 12px;
  font-weight: 500;
  color: #94a3b8;
}
.sample-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
.sample-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 6px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  color: #334155;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
}
.sample-chip:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #0f172a;
}
.sample-chip:active {
  transform: translateY(1px);
}
.feature-strip {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16px;
  font-size: 12px;
  color: #94a3b8;
}
.feature-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.f-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #cbd5e1;
}

/* 生成中 */
.generating-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: auto;
  width: 100%;
  max-width: 580px;
}
.status-box {
  width: 100%;
}
.generating-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  font-size: 12.5px;
  color: #64748b;
}
.pulse-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f97316;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(249, 115, 22, 0.6); }
  70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(249, 115, 22, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(249, 115, 22, 0); }
}

/* 结果卡片 */
.result-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}
.result-badge {
  font-size: 12px;
  font-weight: 600;
  color: #059669;
  background: #ecfdf5;
  padding: 2px 8px;
  border-radius: 4px;
}
.result-hint {
  font-size: 12px;
  color: #94a3b8;
}
.result-content {
  line-height: 1.8;
  color: #1e293b;
  font-size: 14px;
}

/* 响应式 */
@media (max-width: 1024px) {
  .page-container {
    padding: 16px;
    height: auto;
    min-height: 100%;
  }
  .workspace {
    flex-direction: column;
    height: auto;
  }
  .config-panel {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #e2e8f0;
  }
}
</style>
