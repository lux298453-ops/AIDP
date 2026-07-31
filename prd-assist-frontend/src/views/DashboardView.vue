<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { Upload, Document, PictureFilled, Edit, Checked } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: Document, color: '#78716c', bg: 'rgba(120,113,108,0.08)' },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: Edit, color: '#65a30d', bg: 'rgba(101,163,13,0.08)' },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: PictureFilled, color: '#d97706', bg: 'rgba(217,119,6,0.08)' },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: Checked, color: '#b45309', bg: 'rgba(180,83,9,0.08)' },
]

const flowSteps = [
  { label: '上传参考文档', desc: '上传 XMind / Word / 文本等参考素材' },
  { label: '生成 / 增强 PRD', desc: 'AI 自动撰写或补充产品需求文档' },
  { label: '审查 & 导出 Word', desc: '全面评审并一键导出为 Word 文档' },
]
</script>

<template>
  <div class="dashboard">
    <!-- ====== 欢迎区 ====== -->
    <div class="welcome-banner">
      <div class="welcome-left">
        <h2>欢迎回来，{{ user.nickname || user.username }}</h2>
        <p>AI 辅助设计平台 — 让策划工作更高效</p>
      </div>
    </div>

    <!-- ====== 功能模块 ====== -->
    <h3 class="section-title">功能模块</h3>
    <div class="module-grid">
      <div
        v-for="mod in modules"
        :key="mod.path"
        class="module-card"
        @click="router.push(mod.path)"
      >
        <div class="mod-icon-box" :style="{ background: mod.bg, color: mod.color }">
          <el-icon size="24"><component :is="mod.icon" /></el-icon>
        </div>
        <div class="mod-body">
          <h4>{{ mod.title }}</h4>
          <p>{{ mod.desc }}</p>
        </div>
      </div>
    </div>

    <!-- ====== 快捷操作 ====== -->
    <h3 class="section-title">快捷操作</h3>
    <div class="quick-row">
      <div class="quick-card quick-main">
        <div class="quick-card-header">开始工作</div>
        <div class="quick-actions">
          <button class="quick-btn" @click="router.push('/documents')">
            <el-icon size="18"><Upload /></el-icon>
            <span>我的文档</span>
          </button>
          <button class="quick-btn" @click="router.push('/prd/generate')">
            <el-icon size="18"><Document /></el-icon>
            <span>PRD 生成</span>
          </button>
          <button class="quick-btn" @click="router.push('/prototype')">
            <el-icon size="18"><PictureFilled /></el-icon>
            <span>原型生成</span>
          </button>
        </div>
      </div>

      <div class="quick-card quick-flow">
        <div class="quick-card-header">使用流程</div>
        <div class="flow-steps">
          <div
            v-for="(step, idx) in flowSteps"
            :key="idx"
            class="flow-step"
          >
            <div class="flow-indicator">
              <span class="flow-dot">{{ idx + 1 }}</span>
              <div v-if="idx < flowSteps.length - 1" class="flow-line" />
            </div>
            <div class="flow-content">
              <span class="flow-label">{{ step.label }}</span>
              <span class="flow-desc">{{ step.desc }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ====== 页面整体容器 ====== */
.dashboard {
  max-width: 1280px;
  margin: 0 auto;
  padding: 48px 64px;
  background: #faf6f1;
  min-height: 100vh;
}

/* ====== 欢迎横幅 ====== */
.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #e7e5d9;
  border-radius: 2rem;
  padding: 40px 44px;
  color: #44403c;
  margin-bottom: 36px;
  border: 1px solid #d6d3d1;
}
.welcome-left h2 {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.3px;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
  color: #292524;
}
.welcome-left p {
  margin: 0;
  opacity: 0.65;
  font-size: 15px;
  color: #57534e;
}

/* ====== 区段标题 ====== */
.section-title {
  font-size: 13px;
  font-weight: 500;
  color: #a8a29e;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin: 36px 0 16px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* =================================================================
   功能模块卡片
   ================================================================= */
.module-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.module-card {
  display: flex;
  flex-direction: column;
  padding: 28px 24px;
  background: #fff;
  border-radius: 1.5rem;
  border: 1px solid #d6d3d1;
  cursor: pointer;
  transition: all 0.3s ease;
  height: 100%;
}
.module-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(68, 64, 60, 0.1);
  border-color: #a8a29e;
}

.mod-icon-box {
  width: 48px;
  height: 48px;
  border-radius: 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-bottom: 18px;
}

.mod-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.mod-body h4 {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: #292524;
  letter-spacing: -0.1px;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
  flex-shrink: 0;
}
.mod-body p {
  margin: 0;
  font-size: 13px;
  color: #78716c;
  line-height: 1.65;
  flex: 1;
}

/* =================================================================
   快捷操作区
   ================================================================= */
.quick-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
}

.quick-card {
  background: #fff;
  border-radius: 1.5rem;
  border: 1px solid #d6d3d1;
  padding: 24px 28px;
}

.quick-card-header {
  font-size: 15px;
  font-weight: 600;
  color: #292524;
  margin-bottom: 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid #e7e5d9;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.quick-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 52px;
  border: 1px solid #d6d3d1;
  border-radius: 999px;
  background: #faf6f1;
  color: #57534e;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.3s ease;
  text-align: center;
  line-height: 1;
  padding: 0 16px;
}
.quick-btn :deep(.el-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.quick-btn:hover {
  background: #e7e5d9;
  color: #292524;
  border-color: #a8a29e;
  transform: scale(0.97);
}

/* =================================================================
   使用流程
   ================================================================= */
.flow-steps {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.flow-step {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.flow-indicator {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
  width: 28px;
}

.flow-dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #57534e;
  color: #faf6f1;
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.flow-line {
  width: 1.5px;
  flex: 1;
  min-height: 24px;
  background: #d6d3d1;
}

.flow-content {
  display: flex;
  flex-direction: column;
  padding-bottom: 20px;
}
.flow-step:last-child .flow-content {
  padding-bottom: 0;
}

.flow-label {
  font-size: 14px;
  font-weight: 500;
  color: #292524;
  line-height: 28px;
}
.flow-desc {
  font-size: 12px;
  color: #a8a29e;
  line-height: 1.5;
  margin-top: 2px;
}

/* =================================================================
   响应式
   ================================================================= */
@media (max-width: 1000px) {
  .dashboard {
    padding: 32px 24px;
  }
  .module-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .quick-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .module-grid {
    grid-template-columns: 1fr;
  }
  .quick-actions {
    grid-template-columns: 1fr;
  }
}
</style>
