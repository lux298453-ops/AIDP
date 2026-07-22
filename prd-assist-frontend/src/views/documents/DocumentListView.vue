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
const page = ref(0)
const total = ref(0)
const pageSize = 20

// ==================== 加载 ====================
async function loadDocs() {
  loading.value = true
  try {
    const res = await getDocumentList({
      keyword: keyword.value || undefined,
      taskType: taskTypeFilter.value || undefined,
      page: page.value,
      size: pageSize,
    })
    docs.value = res.data.data.content || []
    total.value = res.data.data.totalElements || 0
  } catch { /* handled */ }
  finally { loading.value = false }
}

onMounted(loadDocs)
watch([keyword, taskTypeFilter], () => { page.value = 0; loadDocs() })
watch(page, loadDocs)

// ==================== 导航 ====================
function goDetail(doc: DocumentVO) {
  if (doc.docType === 'PRD') router.push(`/prd/generate`)  // TODO: 详情页
  else if (doc.docType === 'PROTOTYPE') router.push('/prototype')
  else router.push('/prd/review')
}

// ==================== 工具 ====================
const docTypeColors: Record<string, string> = { PRD: '#409eff', PROTOTYPE: '#e6a23c', REVIEW: '#67c23a' }
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
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.docType === 'PRD'" type="primary" size="small" link @click.stop="exportWord(row)">
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
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
      />
    </div>
  </div>
</template>

<style scoped>
.doc-list-page { padding: 24px 32px; max-width: 1400px; margin: 0 auto; }

.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.toolbar h3 { margin: 0; font-size: 18px; font-weight: 600; color: #303133; }
.toolbar-right { display: flex; gap: 10px; align-items: center; }

.doc-card { cursor: pointer; border-radius: 10px; transition: all .2s; border: 1px solid #ebeef5; }
.doc-card:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
.card-tag-row { margin-bottom: 10px; }
.card-title { margin: 0 0 6px; font-size: 14px; font-weight: 600; color: #303133; }
.card-desc { margin: 0 0 10px; font-size: 12px; color: #909399; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-date { font-size: 11px; color: #c0c4cc; }

.el-table { cursor: pointer; border-radius: 8px; overflow: hidden; }
</style>
