<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import ThinkingStatus from '@/components/common/ThinkingStatus.vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Check,
  Clock,
  Delete,
  Grid,
  Iphone,
  Loading,
  Monitor,
  PictureFilled,
  Platform,
  UploadFilled,
} from '@element-plus/icons-vue'
import { getTaskById, getTaskSseUrl } from '@/api/task'
import client from '@/api/client'
import PrototypeResultView from './PrototypeResultView.vue'

const route = useRoute()

interface ProtoPage {
  title: string
  order: number
  html: string
}

interface PrototypeClarifyOption {
  value: string
  label: string
  description?: string
}

interface PrototypeClarifyQuestion {
  id: string
  title: string
  prompt: string
  options?: PrototypeClarifyOption[]
  allowCustomInput?: boolean
  customInputPlaceholder?: string
}

interface PrototypeClarifyResult {
  needsClarification: boolean
  intentSummary?: string
  generationBrief?: string
  assetPlan?: PrototypeAssetPlan
  assetPlans?: PrototypeAssetPlan[]
  questions?: PrototypeClarifyQuestion[]
}

interface PrototypeAssetPlan {
  key?: string
  targetPage?: string
  required: boolean
  source?: 'GENERATED' | 'REFERENCE' | 'NONE'
  role?: string
  prompt?: string
  aspectRatio?: string
  transparentBackground?: boolean
}

interface ClarificationDraft {
  selectedOption: string
  customText: string
}

const description = ref('')
const platform = ref<'APP' | 'WEB' | 'PAD'>('APP')
const generateMode = ref<'single' | 'multi'>('single')

const platforms = [
  { key: 'APP', label: 'APP', icon: Iphone },
  { key: 'WEB', label: 'Web', icon: Monitor },
  { key: 'PAD', label: 'Pad', icon: Platform },
] as const

const imageInput = ref<HTMLInputElement | null>(null)
const referenceImage = ref<File | null>(null)
const referencePreviewUrl = ref('')
const imageDragOver = ref(false)

const clarifying = ref(false)
const clarificationQuestions = ref<PrototypeClarifyQuestion[]>([])
const clarificationDrafts = ref<ClarificationDraft[]>([])
const clarificationSummary = ref('')
const generationBrief = ref('')
const assetPlan = ref<PrototypeAssetPlan | null>(null)
const assetPlans = ref<PrototypeAssetPlan[]>([])
const clarificationResolved = ref(false)

const loading = ref(false)
const opening = ref(false)
const progress = ref(0)
const progressMsg = ref('')
const liveMessages = ref<string[]>([])
const liveContent = ref('')
const showPreview = ref(false)

const singleHtml = ref('')
const pages = ref<ProtoPage[]>([])
const currentPageIndex = ref(0)

const isMultiPage = computed(() => pages.value.length > 0)
const pageTitles = computed(() => pages.value.map((page) => page.title || ''))
const resultId = ref<number | null>(null)
const openingPrototypeId = ref<number | null>(null)
let taskId: number | null = null

const currentHtml = computed<string>({
  get() {
    return isMultiPage.value
      ? (pages.value[currentPageIndex.value]?.html || '')
      : singleHtml.value
  },
  set(value) {
    if (isMultiPage.value) {
      const page = pages.value[currentPageIndex.value]
      if (page) page.html = value
      return
    }
    singleHtml.value = value
  },
})

const canGenerate = computed(() => description.value.trim().length > 0)
const waitingClarification = computed(
  () => clarificationQuestions.value.length > 0 && !clarificationResolved.value,
)
const generateButtonText = computed(() => {
  if (loading.value) return '正在生成...'
  if (clarifying.value) return '正在理解需求...'
  if (waitingClarification.value) return '确认这些选项后继续'
  return '一键生成'
})

const ACCEPTED_IMAGE_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif']
const ACCEPTED_EXTS = ['.jpg', '.jpeg', '.png', '.webp', '.gif']
const MAX_IMAGE_SIZE = 8 * 1024 * 1024

function resetClarificationState() {
  clarificationQuestions.value = []
  clarificationDrafts.value = []
  clarificationSummary.value = ''
  generationBrief.value = ''
  assetPlan.value = null
  assetPlans.value = []
  clarificationResolved.value = false
}

watch(
  () => [
    description.value,
    platform.value,
    generateMode.value,
    referenceImage.value?.name || '',
    referenceImage.value?.size || 0,
  ],
  () => {
    resetClarificationState()
  },
)

function isAcceptedImage(file: File): boolean {
  const name = file.name.toLowerCase()
  const extOk = ACCEPTED_EXTS.some((ext) => name.endsWith(ext))
  const typeOk = !file.type || ACCEPTED_IMAGE_TYPES.includes(file.type.toLowerCase())
  return extOk && typeOk
}

function setReferenceImage(file: File) {
  if (!isAcceptedImage(file)) {
    ElMessage.warning('仅支持 jpg / png / webp / gif 图片')
    return
  }
  if (file.size > MAX_IMAGE_SIZE) {
    ElMessage.warning('参考图不能超过 8MB')
    return
  }
  clearReferenceImage()
  referenceImage.value = file
  referencePreviewUrl.value = URL.createObjectURL(file)
  ElMessage.success(`已选择参考图：${file.name}`)
}

function triggerImageUpload() {
  imageInput.value?.click()
}

function onImageFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    setReferenceImage(input.files[0])
  }
  input.value = ''
}

function clearReferenceImage(event?: Event) {
  event?.stopPropagation()
  if (referencePreviewUrl.value) {
    URL.revokeObjectURL(referencePreviewUrl.value)
    referencePreviewUrl.value = ''
  }
  referenceImage.value = null
  if (imageInput.value) imageInput.value.value = ''
}

function onImageDragOver(event: DragEvent) {
  event.preventDefault()
  imageDragOver.value = true
}

function onImageDragLeave(event: DragEvent) {
  event.preventDefault()
  imageDragOver.value = false
}

function onImageDrop(event: DragEvent) {
  event.preventDefault()
  imageDragOver.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) setReferenceImage(file)
}

let activeEventSource: EventSource | null = null
let activePollTimer: ReturnType<typeof setInterval> | null = null

onBeforeUnmount(() => {
  if (referencePreviewUrl.value) {
    URL.revokeObjectURL(referencePreviewUrl.value)
  }
  activeEventSource?.close()
  activeEventSource = null
  if (activePollTimer) {
    clearInterval(activePollTimer)
    activePollTimer = null
  }
})

let saveTimer: ReturnType<typeof setTimeout> | null = null
watch(
  [singleHtml, pages],
  () => {
    if (!resultId.value) return
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(persist, 1200)
  },
  { deep: true },
)

async function persist() {
  if (!resultId.value) return
  const content = isMultiPage.value
    ? JSON.stringify(pages.value.map((page) => ({ title: page.title, order: page.order, html: page.html })))
    : singleHtml.value
  try {
    await client.put(`/prototype/${resultId.value}`, { content })
  } catch {
    // ignore autosave errors
  }
}

function appendLiveMessage(message?: string) {
  const text = (message || '').trim()
  if (!text) return
  if (liveMessages.value[liveMessages.value.length - 1] === text) return
  liveMessages.value = [...liveMessages.value.slice(-5), text]
}

function resetGenerationState() {
  singleHtml.value = ''
  pages.value = []
  currentPageIndex.value = 0
  resultId.value = null
  progress.value = 0
  liveContent.value = ''
  progressMsg.value = '正在提交原型生成任务...'
  liveMessages.value = ['正在提交原型生成任务...']
  showPreview.value = false
}

function createEmptyClarificationDraft(): ClarificationDraft {
  return {
    selectedOption: '',
    customText: '',
  }
}

function pickClarificationOption(questionIndex: number, optionValue: string) {
  const draft = clarificationDrafts.value[questionIndex]
  if (!draft) return
  draft.selectedOption = optionValue
  if (draft.customText.trim() === optionValue) {
    draft.customText = ''
  }
}

function useCustomClarification(questionIndex: number) {
  const draft = clarificationDrafts.value[questionIndex]
  if (!draft) return
  draft.selectedOption = ''
}

function resolveClarificationAnswer(questionIndex: number): string {
  const draft = clarificationDrafts.value[questionIndex]
  if (!draft) return ''
  const custom = draft.customText.trim()
  if (custom) return custom
  return draft.selectedOption.trim()
}

async function requestClarification(): Promise<PrototypeClarifyResult | null> {
  clarifying.value = true
  try {
    const response = await client.post('/prototype/clarify', {
      description: description.value.trim(),
      platform: platform.value,
      prototypeType: generateMode.value === 'single' ? 'SINGLE_PAGE' : 'MULTI_PAGE',
      hasReferenceImage: !!referenceImage.value,
    }, {
      timeout: 180000,
      silentError: true,
    } as any)
    return response.data?.data as PrototypeClarifyResult
  } catch (error: any) {
    ElMessage.error(error?.code === 'ECONNABORTED'
      ? '需求分析超时，中转站响应较慢，请重试'
      : error?.response?.data?.message || '需求理解失败，请稍后重试')
    return null
  } finally {
    clarifying.value = false
  }
}

async function finalizeClarification(answers: string[]): Promise<PrototypeClarifyResult | null> {
  clarifying.value = true
  try {
    const response = await client.post('/prototype/clarify/finalize', {
      description: description.value.trim(),
      platform: platform.value,
      prototypeType: generateMode.value === 'single' ? 'SINGLE_PAGE' : 'MULTI_PAGE',
      hasReferenceImage: !!referenceImage.value,
      generationBrief: generationBrief.value.trim() || undefined,
      assetPlan: assetPlan.value || undefined,
      assetPlans: assetPlans.value.length ? assetPlans.value : undefined,
      clarificationAnswers: answers,
    }, {
      timeout: 180000,
      silentError: true,
    } as any)
    return response.data?.data as PrototypeClarifyResult
  } catch (error: any) {
    ElMessage.error(error?.code === 'ECONNABORTED'
      ? '最终生成规划超时，中转站响应较慢，请重试'
      : error?.response?.data?.message || '最终生成规划失败，请稍后重试')
    return null
  } finally {
    clarifying.value = false
  }
}

function buildClarificationAnswers(): string[] {
  return clarificationQuestions.value.map((question, index) => {
    const answer = resolveClarificationAnswer(index).trim()
    return `${question.prompt} -> ${answer}`
  })
}

async function submitGenerate(answers: string[] = []) {
  loading.value = true
  resetGenerationState()

  try {
    const prototypeType = generateMode.value === 'single' ? 'SINGLE_PAGE' : 'MULTI_PAGE'

    let response
    if (referenceImage.value) {
      const formData = new FormData()
      formData.append('description', description.value.trim())
      formData.append('prototypeType', prototypeType)
      formData.append('platform', platform.value)
      if (generationBrief.value.trim()) formData.append('generationBrief', generationBrief.value.trim())
      if (assetPlan.value) formData.append('assetPlan', JSON.stringify(assetPlan.value))
      if (assetPlans.value.length) formData.append('assetPlans', JSON.stringify(assetPlans.value))
      answers.forEach((answer) => formData.append('clarificationAnswers', answer))
      formData.append('referenceImage', referenceImage.value)
      response = await client.post('/prototype/generate', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 60000,
      })
    } else {
      response = await client.post('/prototype/generate', {
        description: description.value.trim(),
        prototypeType,
        platform: platform.value,
        clarificationAnswers: answers,
        generationBrief: generationBrief.value.trim() || undefined,
        assetPlan: assetPlan.value || undefined,
        assetPlans: assetPlans.value.length ? assetPlans.value : undefined,
      })
    }

    taskId = response.data.data.taskId
    startSse()
  } catch {
    loading.value = false
    ElMessage.error('生成任务提交失败')
  }
}

async function handleGenerate() {
  if (!description.value.trim()) {
    ElMessage.warning('请先输入功能描述')
    return
  }

  if (waitingClarification.value) {
    const rawAnswers = clarificationQuestions.value.map((_, index) => resolveClarificationAnswer(index))
    const missing = rawAnswers.findIndex((answer) => !answer.trim())
    if (missing >= 0) {
      ElMessage.warning(`请先完成第 ${missing + 1} 个问题`)
      return
    }
    const answers = buildClarificationAnswers()
    const finalPlan = await finalizeClarification(answers)
    if (!finalPlan) return
    clarificationSummary.value = finalPlan.intentSummary || clarificationSummary.value
    generationBrief.value = finalPlan.generationBrief || generationBrief.value
    assetPlan.value = finalPlan.assetPlan || null
    assetPlans.value = finalPlan.assetPlans || []
    clarificationResolved.value = true
    await submitGenerate(answers)
    return
  }

  const clarifyResult = await requestClarification()
  if (!clarifyResult) return

  clarificationSummary.value = clarifyResult.intentSummary || ''
  generationBrief.value = clarifyResult.generationBrief || ''
  assetPlan.value = clarifyResult.assetPlan || null
  assetPlans.value = clarifyResult.assetPlans || []
  clarificationQuestions.value = clarifyResult.questions || []
  clarificationDrafts.value = clarificationQuestions.value.map(() => createEmptyClarificationDraft())

  if (clarifyResult.needsClarification && clarificationQuestions.value.length > 0) {
    clarificationResolved.value = false
    ElMessage.info('我补抓了几个关键点，你选一下就能继续生成')
    return
  }

  clarificationResolved.value = true
  await submitGenerate([])
}

function startSse() {
  if (!taskId) return
  appendLiveMessage('任务已创建，正在连接原型生成服务...')
  activeEventSource?.close()
  const eventSource = new EventSource(getTaskSseUrl(taskId))
  activeEventSource = eventSource
  eventSource.addEventListener('content', (event) => {
    try {
      const data = JSON.parse((event as MessageEvent).data)
      if (data.snapshot) liveContent.value = data.delta || ''
      else liveContent.value += data.delta || ''
    } catch {
      // ignore
    }
  })
  eventSource.addEventListener('progress', (event) => {
    const data = JSON.parse((event as MessageEvent).data)
    progress.value = data.progress
    progressMsg.value = data.message
    appendLiveMessage(data.message)
    if (data.progress <= 0 && data.message && data.message.includes('失败')) {
      eventSource.close()
      loading.value = false
      ElMessage.error(data.message)
      return
    }
    if (data.progress >= 100) {
      eventSource.close()
      loading.value = false
      appendLiveMessage('生成完成，正在加载预览...')
      ElMessage.success('原型生成完成')
      fetchResult()
    }
  })
  eventSource.onerror = () => {
    eventSource.close()
    loading.value = false
  }
}

async function fetchResult() {
  if (!taskId) return
  if (activePollTimer) clearInterval(activePollTimer)
  let failures = 0
  const timer = setInterval(async () => {
    try {
      const response = await getTaskById(taskId as number)
      failures = 0
      const task = response.data.data
      if (task.status === 'SUCCESS' && task.resultRefId) {
        clearInterval(timer)
        activePollTimer = null
        resultId.value = task.resultRefId as number
        const protoResponse = await client.get(`/prototype/${resultId.value}`)
        parseContent(protoResponse.data.data.content || '', protoResponse.data.data.prototypeType)
        showPreview.value = true
      }
      if (task.status === 'FAILED') {
        ElMessage.error(task.errorMessage || '生成失败')
        clearInterval(timer)
        activePollTimer = null
      }
    } catch {
      // 连续失败达到上限后终止轮询，避免无限请求与未捕获异常
      failures += 1
      if (failures >= 5) {
        clearInterval(timer)
        activePollTimer = null
        ElMessage.warning('结果查询失败，请稍后在「我的文档」中查看生成结果')
      }
    }
  }, 2000)
  activePollTimer = timer
}

function parseContent(content: string, protoType: string) {
  singleHtml.value = ''
  pages.value = []
  currentPageIndex.value = 0

  if (!content) {
    singleHtml.value = '<p style="padding:24px;color:#999">暂无内容</p>'
    return
  }

  let decoded: unknown = content
  for (let i = 0; i < 3; i += 1) {
    if (typeof decoded !== 'string') break
    try {
      const parsed = JSON.parse(decoded)
      if (typeof parsed === 'string') {
        decoded = parsed
        continue
      }
      decoded = parsed
      break
    } catch {
      break
    }
  }

  if (typeof decoded === 'string') {
    let html = decoded
    html = html.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\"/g, '"')
    if (!html.trim().startsWith('<') && html.trim().startsWith('[')) {
      try {
        const arr = JSON.parse(html)
        if (Array.isArray(arr)) decoded = arr
      } catch {
        // ignore
      }
    }
  }

  if (typeof decoded === 'object' && decoded !== null) {
    if (Array.isArray(decoded) && decoded.length > 0) {
      pages.value = decoded.map((page: any, index: number) => ({
        title: page.title || `页面 ${page.order || index + 1}`,
        order: page.order || index + 1,
        html: page.html || '',
      }))
      pages.value.sort((a, b) => a.order - b.order)
      generateMode.value = 'multi'
      return
    }
    if ((decoded as any).pages && Array.isArray((decoded as any).pages)) {
      pages.value = (decoded as any).pages.map((page: any, index: number) => ({
        title: page.title || `页面 ${page.order || index + 1}`,
        order: page.order || index + 1,
        html: page.html || '',
      }))
      pages.value.sort((a, b) => a.order - b.order)
      generateMode.value = 'multi'
      return
    }
    if ((decoded as any).html) {
      singleHtml.value = (decoded as any).html
      generateMode.value = 'single'
      return
    }
    singleHtml.value = `<pre style="padding:16px;white-space:pre-wrap">${JSON.stringify(decoded, null, 2).replace(/</g, '&lt;')}</pre>`
    generateMode.value = 'single'
    return
  }

  if (String(protoType).toUpperCase() === 'MULTI_PAGE' && typeof content === 'string') {
    try {
      const arr = JSON.parse(content)
      if (Array.isArray(arr) && arr.length > 0) {
        pages.value = arr.map((page: any, index: number) => ({
          title: page.title || `页面 ${page.order || index + 1}`,
          order: page.order || index + 1,
          html: page.html || '',
        }))
        pages.value.sort((a, b) => a.order - b.order)
        generateMode.value = 'multi'
        return
      }
    } catch {
      // ignore
    }
  }

  singleHtml.value = typeof decoded === 'string' ? decoded : String(content)
  generateMode.value = 'single'
}

function selectPage(index: number) {
  if (index < 0 || index >= pages.value.length) return
  currentPageIndex.value = index
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function loadPrototypeById(id: number) {
  if (!id || Number.isNaN(id) || id <= 0) return
  if (openingPrototypeId.value === id || resultId.value === id) return

  openingPrototypeId.value = id
  opening.value = true
  progressMsg.value = '正在加载原型预览...'
  liveMessages.value = ['正在打开已保存的原型...']
  showPreview.value = false

  try {
    const response = await client.get(`/prototype/${id}`)
    const data = response.data?.data
    if (!data) throw new Error('原型数据为空')

    resultId.value = Number(data.id) || id
    if (data.platform && ['APP', 'WEB', 'PAD'].includes(String(data.platform))) {
      platform.value = String(data.platform) as 'APP' | 'WEB' | 'PAD'
    }
    const protoType = String(data.prototypeType || 'SINGLE_PAGE').toUpperCase()
    generateMode.value = protoType === 'MULTI_PAGE' ? 'multi' : 'single'
    if (!description.value) {
      description.value = `原型 #${resultId.value}（${platform.value}）`
    }
    parseContent(data.content || '', protoType)
    if (!singleHtml.value && pages.value.length === 0) {
      singleHtml.value = '<p style="padding:24px;color:#999">该原型内容为空</p>'
    }
    showPreview.value = true
  } catch (error: any) {
    showPreview.value = false
    ElMessage.error(error?.message || '加载原型失败')
  } finally {
    opening.value = false
    openingPrototypeId.value = null
  }
}

function resolveRoutePrototypeId(): number | null {
  const fromParams = Number(route.params.id)
  if (fromParams > 0) return fromParams
  const fromQuery = Number(route.query.id)
  if (fromQuery > 0) return fromQuery
  return null
}

watch(
  () => [route.params.id, route.query.id] as const,
  () => {
    const id = resolveRoutePrototypeId()
    if (id) loadPrototypeById(id)
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-container">
    <div class="workspace">
      <div class="config-panel">
        <div class="panel-header">
          <div>
            <h2 class="module-title">原型图生成</h2>
            <p class="module-subtitle">高质量交互界面与视觉流生成</p>
          </div>
          <div class="panel-badge" title="交互原型设计引擎">
            <el-icon :size="15"><Grid /></el-icon>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">生成模式</label>
          <div class="mode-btns">
            <div class="mode-btn" :class="{ active: generateMode === 'single' }" @click="generateMode = 'single'">
              单页面
            </div>
            <div class="mode-btn" :class="{ active: generateMode === 'multi' }" @click="generateMode = 'multi'">
              多页面
            </div>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">终端类型</label>
          <div class="platform-cards">
            <div
              v-for="item in platforms"
              :key="item.key"
              class="platform-card"
              :class="{ active: platform === item.key }"
              @click="platform = item.key"
            >
              <el-icon size="24">
                <component :is="item.icon" />
              </el-icon>
              <span>{{ item.label }}</span>
            </div>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">功能描述 <span class="required">*</span></label>
          <el-input
            v-model="description"
            type="textarea"
            :rows="9"
            placeholder="描述你想生成的页面、核心区域、主要交互和视觉重点。我会先理解需求，如果关键信息不够，再只追问 1 到 3 个问题。"
            maxlength="50000"
            show-word-limit
          />
        </div>

        <div v-if="clarificationSummary" class="clarify-summary">
          <p class="clarify-summary__title">当前理解</p>
          <p class="clarify-summary__text">{{ clarificationSummary }}</p>
        </div>

        <div v-if="clarificationQuestions.length > 0" class="clarify-panel">
          <div class="clarify-panel__head">
            <p class="clarify-title">还差几个关键点</p>
            <span class="clarify-badge">先点选项，不满意再自己写</span>
          </div>

          <div class="clarify-card-list">
            <div v-for="(question, index) in clarificationQuestions" :key="question.id || index" class="clarify-card">
              <div class="clarify-card__meta">
                <span class="clarify-card__index">{{ question.title || `问题 ${index + 1}` }}</span>
                <span class="clarify-card__tip">抓大放小，快速补齐就行</span>
              </div>

              <p class="clarify-question">{{ question.prompt }}</p>

              <div v-if="question.options?.length" class="clarify-options">
                <button
                  v-for="option in question.options"
                  :key="option.value"
                  type="button"
                  class="clarify-option"
                  :class="{ active: clarificationDrafts[index]?.selectedOption === option.value && !clarificationDrafts[index]?.customText.trim() }"
                  @click="pickClarificationOption(index, option.value)"
                >
                  <span class="clarify-option__top">
                    <span class="clarify-option__label">{{ option.label }}</span>
                    <el-icon v-if="clarificationDrafts[index]?.selectedOption === option.value && !clarificationDrafts[index]?.customText.trim()" size="14">
                      <Check />
                    </el-icon>
                  </span>
                  <span v-if="option.description" class="clarify-option__desc">{{ option.description }}</span>
                </button>
              </div>

              <div v-if="question.allowCustomInput" class="clarify-custom">
                <button type="button" class="clarify-custom__toggle" @click="useCustomClarification(index)">
                  上面都不合适，我自己补充
                </button>
                <el-input
                  v-model="clarificationDrafts[index].customText"
                  type="textarea"
                  :rows="2"
                  :placeholder="question.customInputPlaceholder || '自己补充一句也可以'"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">
            风格参考图
            <span class="optional-tag">可选</span>
          </label>
          <input
            ref="imageInput"
            type="file"
            accept="image/jpeg,image/jpg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif"
            style="display:none"
            @change="onImageFileSelected"
          >

          <div v-if="referenceImage" class="upload-zone upload-zone--done">
            <div class="ref-preview-wrap">
              <img
                v-if="referencePreviewUrl"
                :src="referencePreviewUrl"
                class="ref-thumb"
                alt="风格参考图预览"
              >
              <div class="ref-meta">
                <p class="upload-text">{{ referenceImage.name }}</p>
                <p class="upload-hint">{{ formatSize(referenceImage.size) }}，生成时会优先参考这张图的视觉风格</p>
              </div>
            </div>
            <el-button class="btn-remove-file" size="small" text type="danger" @click="clearReferenceImage">
              <el-icon style="margin-right:4px">
                <Delete />
              </el-icon>
              删除
            </el-button>
          </div>

          <div
            v-else
            class="upload-zone"
            :class="{ 'upload-zone--drag': imageDragOver }"
            @click="triggerImageUpload"
            @dragover="onImageDragOver"
            @dragleave="onImageDragLeave"
            @drop="onImageDrop"
          >
            <el-icon size="40" color="rgba(38,37,30,0.2)">
              <UploadFilled />
            </el-icon>
            <p class="upload-text">点击上传参考图</p>
            <p class="upload-hint">上传风格参考图后，AI 会优先对齐整体视觉气质</p>
            <p class="upload-hint upload-hint--minor">支持 jpg / png / webp，可拖拽到这里，最大 8MB</p>
          </div>
        </div>

        <el-button
          class="btn-generate"
          :loading="loading || clarifying"
          :disabled="!canGenerate"
          @click="handleGenerate"
        >
          {{ generateButtonText }}
        </el-button>
      </div>

      <div class="preview-panel">
        <template v-if="showPreview && resultId && (singleHtml || pages.length > 0)">
          <PrototypeResultView
            :key="isMultiPage ? `page-${currentPageIndex}` : 'single'"
            v-model="currentHtml"
            :prototype-id="resultId"
            :platform="platform"
            :regenerating="loading"
            :page-titles="pageTitles"
            :page-index="currentPageIndex"
            class="result-view"
            @regenerate="handleGenerate"
            @goto-page="selectPage"
          />
        </template>

        <template v-else>
          <div class="preview-placeholder">
            <el-icon size="60" color="rgba(38,37,30,0.15)">
              <PictureFilled />
            </el-icon>
            <p class="placeholder-title">交互原型会显示在这里</p>
            <p class="placeholder-desc">
              左侧先输入需求，我会先理解场景。只有在信息明显不够时，才会给你几个可选项，帮你快速补齐。
            </p>
          </div>
        </template>

        <div v-if="loading || opening || clarifying" class="preview-placeholder loading-mask generating-state">
          <ThinkingStatus
            v-if="loading || clarifying"
            :steps="clarifying ? ['正在理解需求，判断信息是否已经足够…'] : liveMessages"
          />
          <template v-else>
            <el-icon class="loading-icon" size="54" color="#26251e">
              <Loading />
            </el-icon>
            <p class="generating-desc">{{ progressMsg || '正在加载已保存的原型' }}</p>
          </template>
          <el-skeleton animated style="width:80%;max-width:500px">
            <template #template>
              <div style="display:flex;flex-direction:column;gap:16px;align-items:center">
                <el-skeleton-item variant="rect" style="width:100%;height:200px;border-radius:10px" />
                <el-skeleton-item variant="text" style="width:60%" />
                <el-skeleton-item variant="text" style="width:40%" />
              </div>
            </template>
          </el-skeleton>
        </div>
      </div>
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
  height: 100%;
  display: flex;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08), 0 2px 4px rgba(15, 23, 42, 0.03);
  background: #ffffff;
}

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
  color: #ea580c;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
  transition: all 0.15s ease;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  display: flex;
  align-items: center;
  font-size: 12.5px;
  font-weight: 600;
  color: #334155;
}

.required {
  color: #dc2626;
  margin-left: 2px;
}

.optional-tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: #94a3b8;
  background: #f1f5f9;
  padding: 1px 6px;
  border-radius: 999px;
}

.mode-btns {
  display: flex;
  background: #f1f5f9;
  padding: 3px;
  border: 1px solid rgba(226, 232, 240, 0.8);
  border-radius: 8px;
  gap: 2px;
}

.mode-btn {
  flex: 1;
  text-align: center;
  padding: 6px 0;
  font-size: 12.5px;
  cursor: pointer;
  background: transparent;
  color: #64748b;
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
  border-radius: 6px;
  font-weight: 500;
  border: none;
}

.mode-btn.active {
  background: #ffffff;
  color: #0f172a;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08), 0 0 0 1px rgba(15, 23, 42, 0.04);
}

.mode-btn:hover:not(.active) {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.5);
}

.platform-cards {
  display: flex;
  gap: 8px;
}

.platform-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 14px 6px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  background: #ffffff;
  color: #64748b;
  font-size: 12.5px;
  transition: all 0.15s ease;
}

.platform-card:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
  color: #0f172a;
}

.platform-card.active {
  border-color: #0f172a;
  background: #f8fafc;
  color: #0f172a;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.clarify-summary {
  padding: 12px;
  border-radius: 10px;
  background: #fff7ed;
  border: 1px solid #ffedd5;
}

.clarify-summary__title {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: #ea580c;
}

.clarify-summary__text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #7c2d12;
}

.clarify-panel {
  padding: 14px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.clarify-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.clarify-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.clarify-badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
}

.clarify-card-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.clarify-card {
  padding: 12px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
}

.clarify-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.clarify-card__index {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}

.clarify-card__tip {
  font-size: 11px;
  color: #94a3b8;
}

.clarify-question {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #475569;
}

.clarify-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 8px;
}

.clarify-option {
  width: 100%;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  border-radius: 6px;
  padding: 8px 10px;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
  color: #334155;
}

.clarify-option:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
}

.clarify-option.active {
  border-color: #f97316;
  background: #fff7ed;
  color: #c2410c;
}

.clarify-option.active .clarify-option__desc {
  color: #ea580c;
}

.clarify-option__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.clarify-option__label {
  font-size: 12px;
  font-weight: 500;
  line-height: 1.3;
}

.clarify-option__desc {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.4;
  color: #94a3b8;
}

.clarify-custom {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.clarify-custom__toggle {
  align-self: flex-start;
  border: none;
  background: transparent;
  padding: 0;
  font-size: 12px;
  color: #ea580c;
  cursor: pointer;
  font-weight: 500;
}

.clarify-custom__toggle:hover {
  opacity: 0.85;
}

.upload-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px 14px;
  border: 1.5px dashed #cbd5e1;
  border-radius: 10px;
  background: #f8fafc;
  cursor: pointer;
  transition: all 0.15s ease;
  user-select: none;
}

.upload-zone:hover {
  border-color: #f97316;
  background: #fff7ed;
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
  padding: 12px;
}

.upload-text {
  font-size: 12.5px;
  font-weight: 500;
  color: #0f172a;
  margin: 6px 0 2px;
  text-align: center;
  word-break: break-all;
}

.upload-hint {
  font-size: 11px;
  color: #94a3b8;
  margin: 0;
  text-align: center;
  line-height: 1.4;
}

.upload-hint--minor {
  margin-top: 4px;
}

.btn-remove-file {
  margin-top: 6px;
}

.ref-preview-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.ref-thumb {
  width: 52px;
  height: 52px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  flex-shrink: 0;
}

.ref-meta {
  flex: 1;
  min-width: 0;
  text-align: left;
}

.ref-meta .upload-text {
  margin: 0 0 2px;
  text-align: left;
  padding: 0;
  font-weight: 500;
  color: #0f172a;
}

.ref-meta .upload-hint {
  text-align: left;
}

.btn-generate {
  width: 100%;
  height: 38px;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  border-radius: 7px;
  background: linear-gradient(180deg, #f97316 0%, #ea580c 100%) !important;
  border: 1px solid #c2410c !important;
  color: #ffffff !important;
  cursor: pointer;
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.25), 0 1px 2px 0 rgba(15, 23, 42, 0.08);
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  margin-top: auto;
  user-select: none;
}

.btn-generate:hover:not(:disabled) {
  background: linear-gradient(180deg, #ea580c 0%, #c2410c 100%) !important;
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.2), 0 2px 5px 0 rgba(234, 88, 12, 0.25);
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
  background: #94a3b8 !important;
  border-color: #94a3b8 !important;
}

.preview-panel {
  flex: 1;
  min-width: 0;
  background: #f8fafc;
  display: flex;
  flex-direction: column;
  position: relative;
}

.result-view {
  flex: 1;
  min-height: 0;
}

.preview-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  padding: 40px 24px;
  text-align: center;
}

.placeholder-title {
  font-size: 17px;
  font-weight: 600;
  color: #0f172a;
  margin: 16px 0 6px;
}

.placeholder-desc {
  font-size: 13.5px;
  color: #64748b;
  margin: 0;
  max-width: 420px;
  line-height: 1.6;
}

.preview-placeholder.loading-mask {
  position: absolute;
  inset: 0;
  background: #f8fafc;
  z-index: 10;
}

.loading-icon {
  animation: spin 1s linear infinite;
  color: #0f172a;
}

.generating-desc {
  font-size: 13px;
  color: #64748b;
  margin: 12px 0 0;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

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

  .clarify-options {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
