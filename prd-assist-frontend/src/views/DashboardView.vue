<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { Upload, Document, PictureFilled } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: 'Document', color: '#26251e', bg: 'rgba(38,37,30,0.06)' },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: 'Edit', color: '#1f8a65', bg: 'rgba(31,138,101,0.08)' },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: 'Checked', color: '#c08532', bg: 'rgba(192,133,50,0.08)' },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: 'PictureFilled', color: '#cf2d56', bg: 'rgba(207,45,86,0.06)' },
]

/** 使用流程步骤 */
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
      <!-- 开始工作 -->
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

      <!-- 使用流程 -->
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
}

/* ====== 欢迎横幅 ====== */
.welcome-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #26251e;
  border-radius: 10px;
  padding: 32px 36px;
  color: #f2f1ed;
  margin-bottom: 32px;
}
.welcome-left h2 {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 400;
  letter-spacing: -0.325px;
}
.welcome-left p {
  margin: 0;
  opacity: 0.6;
  font-size: 15px;
  font-family: 'Source Serif 4', 'Iowan Old Style', Georgia, serif;
}

/* ====== 区段标题 ====== */
.section-title {
  font-size: 14px;
  font-weight: 500;
  color: rgba(38, 37, 30, 0.4);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 32px 0 14px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

/* =================================================================
   功能模块卡片 —— 强制等高
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
  border-radius: 8px;
  border: 1px solid rgba(38, 37, 30, 0.1);
  cursor: pointer;
  transition: all 0.2s;

  /* 关键：让整行等高 —— grid 默认 stretch，卡片填满 grid cell */
  height: 100%;
}
.module-card:hover {
  transform: translateY(-2px);
  box-shadow:
    rgba(0, 0, 0, 0.14) 0px 28px 70px,
    rgba(0, 0, 0, 0.1) 0px 14px 32px,
    rgba(38, 37, 30, 0.1) 0px 0px 0px 1px;
}

.mod-icon-box {
  width: 44px;
  height: 44px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-bottom: 16px;
}

/* 内容区 flex 填充剩余空间，保证标题和描述位置统一 */
.mod-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.mod-body h4 {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 400;
  color: #26251e;
  letter-spacing: -0.11px;
  flex-shrink: 0;
}
.mod-body p {
  margin: 0;
  font-size: 13px;
  color: rgba(38, 37, 30, 0.55);
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
  border-radius: 8px;
  border: 1px solid rgba(38, 37, 30, 0.1);
  padding: 20px 24px;
}

.quick-card-header {
  font-size: 15px;
  font-weight: 500;
  color: #26251e;
  margin-bottom: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(38, 37, 30, 0.08);
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
  border: 1px solid rgba(38, 37, 30, 0.1);
  border-radius: 8px;
  background: #f7f7f4;
  color: rgba(38, 37, 30, 0.75);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
  line-height: 1;
  padding: 0 12px;
}
.quick-btn :deep(.el-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.quick-btn:hover {
  background: #ebeae5;
  color: #26251e;
  border-color: rgba(38, 37, 30, 0.2);
}

/* =================================================================
   使用流程 —— 自定义步骤（小而美的序号 + 连接线）
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

/* 左侧：序号圆 + 竖线 */
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
  background: #26251e;
  color: #f2f1ed;
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
  background: rgba(38, 37, 30, 0.15);
}

/* 右侧：文字 */
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
  color: #26251e;
  line-height: 28px; /* 与圆点高度对齐 */
}
.flow-desc {
  font-size: 12px;
  color: rgba(38, 37, 30, 0.45);
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
