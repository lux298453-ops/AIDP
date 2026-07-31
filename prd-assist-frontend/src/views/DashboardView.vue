<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { Upload, Document, PictureFilled, Edit, Checked } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: Document, color: '#5a8a7a', bg: 'rgba(90,138,122,0.12)' },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: Edit, color: '#8b7a5a', bg: 'rgba(139,122,90,0.12)' },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: PictureFilled, color: '#c07a5a', bg: 'rgba(192,122,90,0.10)' },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: Checked, color: '#7a8b5a', bg: 'rgba(122,139,90,0.12)' },
]

const flowSteps = [
  { label: '上传参考文档', desc: '上传 XMind / Word / 文本等参考素材' },
  { label: '生成 / 增强 PRD', desc: 'AI 自动撰写或补充产品需求文档' },
  { label: '审查 & 导出 Word', desc: '全面评审并一键导出为 Word 文档' },
]
</script>

<template>
  <div class="dashboard">
    <!-- 装饰云朵 -->
    <div class="cloud cloud-1" />
    <div class="cloud cloud-2" />

    <!-- ====== 欢迎区 ====== -->
    <div class="welcome-banner">
      <div class="welcome-left">
        <h2>ようこそ、{{ user.nickname || user.username }}</h2>
        <p>AI 辅助设计平台 — 让策划工作更高效</p>
      </div>
      <div class="welcome-decor">
        <svg viewBox="0 0 120 80" class="welcome-illust">
          <ellipse cx="60" cy="70" rx="50" ry="8" fill="rgba(124,185,168,0.15)" />
          <circle cx="40" cy="30" r="18" fill="rgba(124,185,168,0.25)" />
          <circle cx="65" cy="22" r="22" fill="rgba(124,185,168,0.3)" />
          <circle cx="90" cy="32" r="16" fill="rgba(124,185,168,0.2)" />
        </svg>
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
  position: relative;
  overflow: hidden;
}

/* ====== 装饰云朵 ====== */
.cloud {
  position: absolute;
  border-radius: 50%;
  background: rgba(124, 185, 168, 0.08);
  pointer-events: none;
  z-index: 0;
}
.cloud-1 {
  width: 200px;
  height: 80px;
  top: -20px;
  right: -40px;
}
.cloud-2 {
  width: 160px;
  height: 60px;
  bottom: 60px;
  left: -30px;
}

/* ====== 欢迎横幅 ====== */
.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #5a8a7a 0%, #7cb9a8 40%, #a8d5c8 100%);
  border-radius: 24px;
  padding: 36px 40px;
  color: #faf5eb;
  margin-bottom: 36px;
  position: relative;
  z-index: 1;
  box-shadow: 0 4px 24px rgba(90, 138, 122, 0.2);
}
.welcome-left h2 {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.welcome-left p {
  margin: 0;
  opacity: 0.8;
  font-size: 15px;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
}
.welcome-decor {
  flex-shrink: 0;
  opacity: 0.6;
}
.welcome-illust {
  width: 100px;
  height: 66px;
}

/* ====== 区段标题 ====== */
.section-title {
  font-size: 13px;
  font-weight: 500;
  color: rgba(58, 50, 38, 0.4);
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin: 36px 0 16px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  position: relative;
  z-index: 1;
}

/* =================================================================
   功能模块卡片
   ================================================================= */
.module-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  position: relative;
  z-index: 1;
}

.module-card {
  display: flex;
  flex-direction: column;
  padding: 24px;
  background: #faf5eb;
  border-radius: 20px;
  border: 1px solid rgba(124, 185, 168, 0.3);
  cursor: pointer;
  transition: all 0.3s ease-out;
  height: 100%;
  box-shadow: 0 2px 12px rgba(124, 185, 168, 0.1);
}
.module-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 28px rgba(124, 185, 168, 0.2);
  border-color: rgba(124, 185, 168, 0.5);
  background: #fff;
}

.mod-icon-box {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-bottom: 16px;
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
  color: #3a3226;
  letter-spacing: 0;
  flex-shrink: 0;
}
.mod-body p {
  margin: 0;
  font-size: 13px;
  color: rgba(58, 50, 38, 0.5);
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
  position: relative;
  z-index: 1;
}

.quick-card {
  background: #faf5eb;
  border-radius: 20px;
  border: 1px solid rgba(124, 185, 168, 0.3);
  padding: 20px 24px;
  box-shadow: 0 2px 12px rgba(124, 185, 168, 0.1);
}

.quick-card-header {
  font-size: 15px;
  font-weight: 600;
  color: #3a3226;
  margin-bottom: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(124, 185, 168, 0.25);
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
  border: 1px solid rgba(124, 185, 168, 0.3);
  border-radius: 16px;
  background: #fff;
  color: #3a3226;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.3s ease-out;
  text-align: center;
  line-height: 1;
  padding: 0 12px;
  box-shadow: 0 2px 8px rgba(124, 185, 168, 0.08);
}
.quick-btn :deep(.el-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #5a8a7a;
}
.quick-btn:hover {
  background: #f4e4bc;
  border-color: rgba(124, 185, 168, 0.5);
  box-shadow: 0 4px 16px rgba(124, 185, 168, 0.2);
  transform: scale(1.02);
}
.quick-btn:active {
  transform: scale(0.98);
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
  background: #5a8a7a;
  color: #faf5eb;
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
  background: rgba(124, 185, 168, 0.3);
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
  color: #3a3226;
  line-height: 28px;
}
.flow-desc {
  font-size: 12px;
  color: rgba(58, 50, 38, 0.45);
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
}
</style>
