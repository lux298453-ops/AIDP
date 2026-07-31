<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { Upload, Document, PictureFilled, Edit, Checked } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: Document, color: '#1e40af' },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: Edit, color: '#10b981' },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: PictureFilled, color: '#c559f0' },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: Checked, color: '#f59e0b' },
]

const flowSteps = [
  { label: '上传参考文档', desc: '上传 XMind / Word / 文本等参考素材' },
  { label: '生成 / 增强 PRD', desc: 'AI 自动撰写或补充产品需求文档' },
  { label: '审查 & 导出 Word', desc: '全面评审并一键导出为 Word 文档' },
]
</script>

<template>
  <div class="dashboard">
    <!-- ====== 欢迎横幅 ====== -->
    <div class="welcome-banner">
      <div class="welcome-left">
        <h2>欢迎回来，{{ user.nickname || user.username }}</h2>
        <p>AI 辅助设计平台 — 让策划工作更高效</p>
      </div>
      <div class="welcome-right">
        <div class="welcome-stat">
          <span class="welcome-stat-value">4</span>
          <span class="welcome-stat-label">功能模块</span>
        </div>
        <div class="welcome-stat">
          <span class="welcome-stat-value">3</span>
          <span class="welcome-stat-label">工作步骤</span>
        </div>
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
        <div class="mod-icon-box" :style="{ color: mod.color }">
          <el-icon size="22"><component :is="mod.icon" /></el-icon>
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
      <div class="quick-card">
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

      <div class="quick-card">
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
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 48px;
}

/* ====== 欢迎横幅 ====== */
.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #1e40af, #3b82f6);
  border-radius: 12px;
  padding: 36px 40px;
  color: #fff;
  margin-bottom: 36px;
  box-shadow: 0 1px 3px rgba(30, 64, 175, 0.12);
}
.welcome-left h2 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.3px;
}
.welcome-left p {
  margin: 0;
  opacity: 0.8;
  font-size: 14px;
}
.welcome-right {
  display: flex;
  gap: 32px;
}
.welcome-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.welcome-stat-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
}
.welcome-stat-label {
  font-size: 12px;
  opacity: 0.7;
}

/* ====== 区段标题 ====== */
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin: 0 0 16px;
}

/* =================================================================
   功能模块卡片
   ================================================================= */
.module-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 36px;
}

.module-card {
  display: flex;
  flex-direction: column;
  padding: 24px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
  cursor: pointer;
  transition: all 0.2s ease-out;
  height: 100%;
}
.module-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.1);
  border-color: #cbd5e1;
}

.mod-icon-box {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-bottom: 16px;
  background: #f1f5f9;
  transition: all 0.2s ease-out;
}
.module-card:hover .mod-icon-box {
  background: #1e40af;
  color: #fff !important;
  transform: scale(1.08);
}

.mod-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.mod-body h4 {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: -0.1px;
  flex-shrink: 0;
}
.mod-body p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
  line-height: 1.55;
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
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
  padding: 24px;
}

.quick-card-header {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
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
  height: 48px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
  color: #334155;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s ease-out;
  text-align: center;
  line-height: 1;
  padding: 0 12px;
}
.quick-btn :deep(.el-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #64748b;
  transition: color 0.15s ease-out;
}
.quick-btn:hover {
  background: #1e40af;
  color: #fff;
  border-color: #1e40af;
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(30, 64, 175, 0.15);
}
.quick-btn:hover :deep(.el-icon) {
  color: #fff;
}
.quick-btn:active {
  transform: scale(0.98) translateY(0);
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
  background: #1e40af;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.flow-line {
  width: 1.5px;
  flex: 1;
  min-height: 24px;
  background: #e2e8f0;
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
  font-weight: 600;
  color: #0f172a;
  line-height: 28px;
}
.flow-desc {
  font-size: 12px;
  color: #64748b;
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
  .welcome-banner {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
  .welcome-right {
    gap: 24px;
  }
}
</style>
