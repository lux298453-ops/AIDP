<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDocumentList } from '@/api/document'
import client from '@/api/client'
import type { DocumentVO } from '@/types/document'

const router = useRouter()

// ==================== 视图模式 ====================
const viewMode = ref<'list' | 'card'>('list')

// ==================== 筛选 + 搜索 ====================
const keyword = ref('')
const taskTypeFilter = ref('')
const taskTypeOptions = [
  { label: '全部类型', value: '' },
  { label: 'PRD 生成', value: 'PRD_GENERATE' },
  { label: '原型图', value: 'PROTOTYPE' },
  { label: '审查报告', value: 'PRD_REVIEW' },
]

// ==================== 分页 ====================
const docs = ref<DocumentVO[]>([])
const loading = ref(false)
const currentPage = ref(1)
const total = ref(0)
const pageSize = 20

// ==================== 加载 ====================
async function loadDocs() {
  loading.value = true
  try {
    const res = await getDocumentList({
      keyword: keyword.value || undefined,
      taskType: taskTypeFilter.value || undefined,
      page: Math.max(0, currentPage.value - 1),
      size: pageSize,
    })
    docs.value = res.data.data.content || []
    total.value = res.data.data.totalElements || 0
    const maxPage = Math.max(1, Math.ceil(total.value / pageSize))
    if (currentPage.value > maxPage) {
      currentPage.value = maxPage
    }
  } catch { /* handled */ }
  finally { loading.value = false }
}

onMounted(loadDocs)
watch([keyword, taskTypeFilter], () => { currentPage.value = 1; loadDocs() })
watch(currentPage, loadDocs)

// ==================== 导航：跳到对应模块的预览/详情页 ====================
function goDetail(doc: DocumentVO) {
  const type = String(doc?.docType || '').toUpperCase()
  const id = Number(doc?.id)
  if (!id) {
    ElMessage.warning('文档 ID 无效')
    return
  }

  if (type === 'PRD') {
    router.push(`/prd/${id}`)
    return
  }
  if (type === 'PROTOTYPE') {
    // 专用详情路由，避免 query 丢失 / 组件复用导致不加载
    router.push(`/prototype/${id}`)
    return
  }
  if (type === 'REVIEW') {
    router.push({ path: '/prd/review', query: { reportId: String(id) } })
    return
  }
  ElMessage.warning(`未知文档类型：${doc?.docType || '空'}`)
}

// ==================== 工具 ====================
const docTypeColors: Record<string, string> = { PRD: '#26251e', PROTOTYPE: '#c08532', REVIEW: '#1f8a65' }
const docTypeLabels: Record<string, string> = { PRD: 'PRD', PROTOTYPE: '原型', REVIEW: '审查' }

function formatDate(s: string) { return s ? s.substring(0, 10) + ' ' + s.substring(11, 16) : '' }

/** 导出 Word（通过 axios 下载，携带 JWT token） */
async function exportWord(doc: DocumentVO) {
  try {
    const res = await client.get(`/prd/${doc.id}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = `${doc.title}.docx`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('下载成功')
  } catch {
    ElMessage.error('下载失败')
  }
}

/** 导出原型 HTML */
async function exportPrototype(doc: DocumentVO) {
  try {
    const res = await client.get(`/prototype/${doc.id}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data], { type: 'text/html' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${doc.title}.html`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

/** 导出审查报告 Word */
async function exportReview(doc: DocumentVO) {
  try {
    const res = await client.get(`/review/${doc.id}/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = `${doc.title}.docx`
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

/** 重新生成 */
async function handleRegenerate(doc: DocumentVO) {
  if (!doc.taskId) return
  try {
    await ElMessageBox.confirm(
      `将基于原有参数重新生成「${doc.title}」，旧结果会保留。确认继续？`,
      '重新生成',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'info' }
    )
    const res = await client.post(`/task/${doc.taskId}/regenerate`)
    const newTaskId = res.data?.data?.taskId
    ElMessage.success(`重新生成已提交 (taskId=${newTaskId})，请稍候查看`)
    setTimeout(loadDocs, 3000)
  } catch {
    // 用户取消或失败
  }
}
</script>

<template>
  <div class="doc-list-page">
    <!-- 顶栏 -->
    <div class="toolbar">
      <h3>我的文档</h3>
      <div class="toolbar-right">
        <el-input v-model="keyword" placeholder="搜索标题..." clearable style="width:240px" size="default">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="taskTypeFilter" style="width:140px" size="default">
          <el-option v-for="o in taskTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-radio-group v-model="viewMode" size="default">
          <el-radio-button value="list"><el-icon><List /></el-icon></el-radio-button>
          <el-radio-button value="card"><el-icon><Grid /></el-icon></el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- 列表视图 -->
    <template v-if="viewMode === 'list'">
      <el-table :data="docs" v-loading="loading" stripe style="width:100%" @row-click="goDetail">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :color="docTypeColors[row.docType]" size="small" style="color:#fff;border:none">
              {{ docTypeLabels[row.docType] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="160" :formatter="(_r: any, _c: any, v: string) => formatDate(v)" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click.stop="goDetail(row)">
              打开
            </el-button>
            <el-button v-if="row.docType === 'PRD'" type="primary" size="small" link @click.stop="exportWord(row)">
              <el-icon><Download /></el-icon> 导出
            </el-button>
            <el-button v-if="row.docType === 'PROTOTYPE'" type="primary" size="small" link @click.stop="exportPrototype(row)">
              <el-icon><Download /></el-icon> 导出
            </el-button>
            <el-button v-if="row.docType === 'REVIEW'" type="primary" size="small" link @click.stop="exportReview(row)">
              <el-icon><Download /></el-icon> 导出
            </el-button>
            <el-button v-if="row.taskId" type="warning" size="small" link @click.stop="handleRegenerate(row)">
              <el-icon><Refresh /></el-icon> 重新生成
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="docs.length === 0 && !loading" style="text-align:center;padding:60px;color:#909399">暂无文档</div>
    </template>

    <!-- 卡片视图 -->
    <template v-if="viewMode === 'card'">
      <el-row :gutter="16" v-loading="loading">
        <el-col v-for="doc in docs" :key="doc.docType + doc.id" :span="6" style="margin-bottom:16px">
          <el-card class="doc-card" shadow="never" @click="goDetail(doc)">
            <div class="card-tag-row">
              <el-tag :color="docTypeColors[doc.docType]" size="small" style="color:#fff;border:none">
                {{ docTypeLabels[doc.docType] }}
              </el-tag>
            </div>
            <h4 class="card-title">{{ doc.title }}</h4>
            <p class="card-desc">{{ doc.description || '暂无描述' }}</p>
            <span class="card-date">{{ formatDate(doc.createdAt) }}</span>
          </el-card>
        </el-col>
      </el-row>
      <div v-if="docs.length === 0 && !loading" style="text-align:center;padding:60px;color:#909399">暂无文档</div>
    </template>

    <!-- 分页 -->
    <div v-if="total > pageSize" style="display:flex;justify-content:center;margin-top:20px">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
      />
    </div>
  </div>
</template>

<style scoped>
.doc-list-page { padding: 32px 48px; max-width: 1400px; margin: 0 auto; }

.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.toolbar h3 { margin: 0; font-size: 22px; font-weight: 400; color: #26251e; letter-spacing: -0.11px; }
.toolbar-right { display: flex; gap: 10px; align-items: center; }

.doc-card { cursor: pointer; border-radius: 8px; transition: all .2s; border: 1px solid rgba(38, 37, 30, 0.1); background: #fff !important; }
.doc-card:hover { transform: translateY(-2px); box-shadow: rgba(0, 0, 0, 0.14) 0px 28px 70px, rgba(0, 0, 0, 0.1) 0px 14px 32px, rgba(38, 37, 30, 0.1) 0px 0px 0px 1px !important; }
.card-tag-row { margin-bottom: 10px; }
.card-title { margin: 0 0 6px; font-size: 15px; font-weight: 400; color: #26251e; }
.card-desc { margin: 0 0 10px; font-size: 13px; color: rgba(38, 37, 30, 0.55); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-date { font-size: 11px; color: rgba(38, 37, 30, 0.4); }

.el-table { cursor: pointer; border-radius: 8px; overflow: hidden; }
</style>
