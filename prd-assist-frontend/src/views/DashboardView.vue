<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { Upload, Document, PictureFilled, Edit, Checked } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: Document, color: '#2d5016', bg: 'rgba(45,80,22,0.08)' },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: Edit, color: '#1f8a65', bg: 'rgba(31,138,101,0.08)' },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: PictureFilled, color: '#e07b39', bg: 'rgba(224,123,57,0.08)' },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: Checked, color: '#c08532', bg: 'rgba(192,133,50,0.08)' },
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
      <div class="welcome-decor">
        <span class="decor-leaf">&#x1F33F;</span>
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
  background: #fef9f0;
  min-height: 100%;
}

/* ====== 欢迎横幅 ====== */
.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #2d5016 0%, #3d6b1e 50%, #4a7a28 100%);
  border-radius: 20px;
  padding: 36px 40px;
  color: #fef9f0;
  margin-bottom: 36px;
  box-shadow: 0 4px 20px rgba(45, 80, 22, 0.15);
  position: relative;
  overflow: hidden;
}
.welcome-banner::before {
  content: '';
  position: absolute;
  top: -40px;
  right: -40px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.04);
}
.welcome-banner::after {
  content: '';
  position: absolute;
  bottom: -60px;
  right: 80px;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.03);
}
.welcome-left {
  position: relative;
  z-index: 1;
}
.welcome-left h2 {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.3px;
}
.welcome-left p {
  margin: 0;
  opacity: 0.75;
  font-size: 15px;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
}
.welcome-decor {
  position: relative;
  z-index: 1;
}
.decor-leaf {
  font-size: 48px;
  opacity: 0.3;
  filter: grayscale(0.3);
}

/* ====== 区段标题 ====== */
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #8b7355;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin: 36px 0 16px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-title::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: #2d5016;
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
  padding: 24px;
  background: #fff;
  border-radius: 16px;
  border: 1px solid #d4e4bc;
  cursor: pointer;
  transition: all 0.2s ease;
  height: 100%;
  box-shadow: 0 1px 3px rgba(45, 80, 22, 0.06);
}
.module-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(45, 80, 22, 0.1);
  border-color: #a8c97e;
}
.module-card:active {
  transform: scale(0.98);
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
  color: #2d5016;
  letter-spacing: -0.1px;
  flex-shrink: 0;
}
.mod-body p {
  margin: 0;
  font-size: 13px;
  color: #8b7355;
  line-height: 1.6;
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
  border-radius: 16px;
  border: 1px solid #d4e4bc;
  padding: 20px 24px;
  box-shadow: 0 1px 3px rgba(45, 80, 22, 0.06);
}

.quick-card-header {
  font-size: 15px;
  font-weight: 600;
  color: #2d5016;
  margin-bottom: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid #d4e4bc;
}

/* 快捷按钮行 */
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
  border: 1px solid #d4e4bc;
  border-radius: 999px;
  background: #fef9f0;
  color: #5a6e3a;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s ease;
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
  background: #2d5016;
  color: #fef9f0;
  border-color: #2d5016;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(45, 80, 22, 0.15);
}
.quick-btn:active {
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
  background: #2d5016;
  color: #fef9f0;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.flow-line {
  width: 2px;
  flex: 1;
  min-height: 24px;
  background: #d4e4bc;
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
  color: #2d5016;
  line-height: 28px;
}
.flow-desc {
  font-size: 12px;
  color: #8b7355;
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
    padding: 24px 20px;
  }
  .welcome-left h2 {
    font-size: 22px;
  }
}
</style>
