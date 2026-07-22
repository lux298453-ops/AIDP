<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: 'Document', color: '#409eff', bg: '#ecf5ff' },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: 'Edit', color: '#67c23a', bg: '#f0f9eb' },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: 'Checked', color: '#e6a23c', bg: '#fdf6ec' },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: 'PictureFilled', color: '#f56c6c', bg: '#fef0f0' },
]
</script>

<template>
  <div class="dashboard">
    <!-- 欢迎区 -->
    <div class="welcome-banner">
      <div class="welcome-left">
        <h2>👋 欢迎回来，{{ user.nickname || user.username }}</h2>
        <p>AI 辅助设计平台 — 让策划工作更高效</p>
      </div>
    </div>

    <!-- 功能模块 -->
    <h3 class="section-title">功能模块</h3>
    <el-row :gutter="16">
      <el-col v-for="mod in modules" :key="mod.path" :span="6">
        <el-card class="module-card" shadow="never" @click="router.push(mod.path)">
          <div class="mod-icon-box" :style="{ background: mod.bg, color: mod.color }">
            <el-icon size="24"><component :is="mod.icon" /></el-icon>
          </div>
          <h4>{{ mod.title }}</h4>
          <p>{{ mod.desc }}</p>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <h3 class="section-title">快捷操作</h3>
    <el-row :gutter="16">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header><span style="font-weight:600">开始工作</span></template>
          <el-row :gutter="12">
            <el-col :span="8">
              <el-button size="large" style="width:100%;height:52px" @click="router.push('/documents')">
                <el-icon><Upload /></el-icon> 上传文档
              </el-button>
            </el-col>
            <el-col :span="8">
              <el-button size="large" style="width:100%;height:52px" @click="router.push('/prd/generate')">
                <el-icon><Document /></el-icon> PRD 生成
              </el-button>
            </el-col>
            <el-col :span="8">
              <el-button size="large" style="width:100%;height:52px" @click="router.push('/prototype')">
                <el-icon><PictureFilled /></el-icon> 原型生成
              </el-button>
            </el-col>
          </el-row>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span style="font-weight:600">使用流程</span></template>
          <el-steps direction="vertical" :space="24" style="font-size:13px">
            <el-step title="上传参考文档" status="finish" />
            <el-step title="生成 / 增强 PRD" status="process" />
            <el-step title="审查 & 导出 Word" status="wait" />
          </el-steps>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* ====== 页面整体容器：居中 + 左右呼吸边距 ====== */
.dashboard {
  max-width: 1280px;
  margin: 0 auto;
  padding: 32px 64px;
}

/* 欢迎横幅 */
.welcome-banner {
  display: flex; align-items: center; justify-content: space-between;
  background: linear-gradient(135deg, #409eff, #337ecc);
  border-radius: 12px; padding: 28px 32px; color: #fff; margin-bottom: 24px;
}
.welcome-left h2 { margin: 0 0 6px; font-size: 20px; font-weight: 600; }
.welcome-left p { margin: 0; opacity: 0.85; font-size: 14px; }

/* 标题 */
.section-title { font-size: 16px; font-weight: 600; color: #303133; margin: 8px 0 14px; }

/* 功能卡片 */
.module-card {
  cursor: pointer; border-radius: 10px; transition: all 0.2s; border: 1px solid #ebeef5;
}
.module-card:hover { transform: translateY(-3px); box-shadow: 0 4px 16px rgba(0,0,0,0.08); }
.mod-icon-box {
  width: 44px; height: 44px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center; margin-bottom: 12px;
}
.module-card h4 { margin: 0 0 6px; font-size: 15px; color: #303133; }
.module-card p { margin: 0; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
