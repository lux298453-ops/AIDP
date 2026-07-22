<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '@/api/client'

interface Chapter { title: string; content: string; type?: string }
interface PrdState { title: string; summary: string; chapters: Chapter[] }

const route = useRoute()
const router = useRouter()
const id = computed(() => Number(route.params.id))
const title = ref('')
const description = ref('')
const summary = ref('')
const chapters = ref<Chapter[]>([])
const compare = ref<PrdState | null>(null)
const compareLoading = ref(false)
const loading = ref(true)
const saving = ref(false)

function parseContent(raw: any): PrdState {
  let value = raw
  for (let i = 0; i < 3 && typeof value === 'string'; i++) {
    try { value = JSON.parse(value) } catch { break }
  }
  return {
    title: value?.title || '',
    summary: value?.summary || '',
    chapters: Array.isArray(value?.chapters) ? value.chapters.map((c: any) => ({
      title: c?.title || '未命名章节', content: c?.content || '', type: c?.type,
    })) : [],
  }
}

async function loadPrd(targetId: number): Promise<PrdState> {
  const response = await client.get(`/prd/${targetId}`)
  const data = response.data.data
  const parsed = parseContent(data.content)
  return { ...parsed, title: data.title || parsed.title }
}

async function load() {
  loading.value = true
  try {
    const current = await loadPrd(id.value)
    title.value = current.title; description.value = (await client.get(`/prd/${id.value}`)).data.data.description || ''
    summary.value = current.summary; chapters.value = current.chapters
    const compareId = Number(route.query.compare)
    if (compareId && compareId !== id.value) {
      compareLoading.value = true
      try { compare.value = await loadPrd(compareId) } finally { compareLoading.value = false }
    }
  } catch { ElMessage.error('PRD 加载失败') }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  try {
    await client.put(`/prd/${id.value}`, {
      title: title.value,
      description: description.value,
      content: { title: title.value, summary: summary.value, chapters: chapters.value },
    })
    ElMessage.success('已保存')
  } catch { ElMessage.error('保存失败') }
  finally { saving.value = false }
}

function addChapter() { chapters.value.push({ title: '新章节', content: '' }) }
function removeChapter(index: number) { chapters.value.splice(index, 1) }

async function exportWord() {
  try {
    const response = await client.get(`/prd/${id.value}/export`, { responseType: 'blob' })
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${title.value || 'PRD'}.docx`; anchor.click()
    URL.revokeObjectURL(url); ElMessage.success('Word 导出成功')
  } catch { ElMessage.error('Word 导出失败') }
}

async function regenerate() {
  try {
    await ElMessageBox.confirm('将保留当前版本并重新提交生成，是否继续？', '重新生成', { type: 'info' })
    const taskId = (await client.post(`/task/${(await client.get(`/prd/${id.value}`)).data.data.taskId}/regenerate`)).data.data.taskId
    ElMessage.success(`已提交任务 ${taskId}`)
  } catch { /* cancelled */ }
}

onMounted(load)
</script>

<template>
  <div class="prd-detail-page" v-loading="loading">
    <div class="detail-toolbar">
      <el-button link @click="router.push('/documents')"><el-icon><ArrowLeft /></el-icon>我的文档</el-button>
      <div class="toolbar-actions">
        <el-button :loading="saving" type="primary" @click="save"><el-icon><Check /></el-icon>保存</el-button>
        <el-button @click="exportWord"><el-icon><Download /></el-icon>导出 Word</el-button>
        <el-button @click="regenerate"><el-icon><Refresh /></el-icon>重新生成</el-button>
      </div>
    </div>

    <div class="detail-layout" v-if="!loading">
      <main class="editor-column">
        <div class="title-row">
          <el-input v-model="title" class="title-input" placeholder="PRD 标题" />
          <span class="doc-id">#{{ id }}</span>
        </div>
        <el-input v-model="description" type="textarea" :rows="2" placeholder="文档描述" class="description-input" />
        <section class="editor-section">
          <h3>一句话需求</h3>
          <el-input v-model="summary" type="textarea" :rows="3" placeholder="填写需求价值和目标" />
        </section>
        <section v-for="(chapter, index) in chapters" :key="index" class="chapter-editor">
          <div class="chapter-header">
            <el-input v-model="chapter.title" class="chapter-title" />
            <el-button text type="danger" @click="removeChapter(index)"><el-icon><Delete /></el-icon></el-button>
          </div>
          <el-input v-model="chapter.content" type="textarea" :rows="8" resize="vertical" />
        </section>
        <el-button class="add-chapter" plain @click="addChapter"><el-icon><Plus /></el-icon>新增章节</el-button>
      </main>

      <aside class="compare-column" v-if="route.query.compare">
        <div class="compare-heading"><span>原始版本</span><span v-if="compareLoading">加载中...</span></div>
        <template v-if="compare">
          <h3>{{ compare.title }}</h3>
          <p class="compare-summary">{{ compare.summary }}</p>
          <article v-for="(chapter, index) in compare.chapters" :key="index" class="compare-chapter">
            <h4>{{ chapter.title }}</h4><p>{{ chapter.content }}</p>
          </article>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.prd-detail-page { min-height: 100%; padding: 24px 36px; background: #f5f7fa; box-sizing: border-box; }
.detail-toolbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:18px; }
.toolbar-actions { display:flex; gap:10px; }
.detail-layout { display:grid; grid-template-columns:minmax(0, 1fr) minmax(320px, .8fr); gap:20px; align-items:start; }
.editor-column, .compare-column { background:#fff; padding:24px; border:1px solid #ebeef5; border-radius:8px; }
.editor-column { min-width:0; }
.title-row { display:flex; align-items:center; gap:12px; margin-bottom:12px; }
.title-input :deep(input) { font-size:22px; font-weight:600; }
.doc-id { color:#909399; font-size:12px; }
.description-input { margin-bottom:20px; }
.editor-section { border-top:1px solid #ebeef5; padding-top:18px; margin-top:18px; }
.editor-section h3 { font-size:15px; margin:0 0 10px; }
.chapter-editor { border-top:1px solid #ebeef5; padding:18px 0; }
.chapter-header { display:flex; gap:8px; align-items:center; margin-bottom:10px; }
.chapter-title { font-weight:600; }
.add-chapter { margin-top:8px; }
.compare-column { max-height:calc(100vh - 130px); overflow:auto; background:#fbfcfe; }
.compare-heading { display:flex; justify-content:space-between; color:#909399; font-size:13px; border-bottom:1px solid #ebeef5; padding-bottom:12px; }
.compare-column h3 { font-size:18px; margin:16px 0 8px; }
.compare-summary { color:#606266; line-height:1.6; }
.compare-chapter { border-top:1px solid #ebeef5; padding:12px 0; }
.compare-chapter h4 { margin:0 0 6px; font-size:14px; }
.compare-chapter p { white-space:pre-wrap; color:#606266; line-height:1.7; margin:0; }
@media (max-width: 900px) { .detail-layout { grid-template-columns:1fr; } .compare-column { max-height:none; } .detail-toolbar { align-items:flex-start; gap:12px; flex-direction:column; } }
</style>
