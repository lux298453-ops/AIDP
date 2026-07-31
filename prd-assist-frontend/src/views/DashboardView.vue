<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { Upload, Document, PictureFilled, Edit, Checked } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()

const modules = [
  { title: 'PRD 生成', desc: '输入需求描述，AI 自动生成结构化 PRD 文档', path: '/prd/generate', icon: Document },
  { title: 'PRD 增强', desc: '在已有 PRD 基础上补充、细化、优化内容', path: '/prd/enhance', icon: Edit },
  { title: '原型生成', desc: '根据 PRD 生成可交互的 HTML 原型页面', path: '/prototype', icon: PictureFilled },
  { title: 'PRD 审查', desc: 'AI 全面评审 PRD，检查完整性与一致性', path: '/prd/review', icon: Checked },
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
    <section class="welcome-section">
      <div class="welcome-inner">
        <h1 class="welcome-heading">欢迎回来，{{ user.nickname || user.username }}</h1>
        <p class="welcome-sub">AI 辅助设计平台 — 让策划工作更高效</p>
      </div>
    </section>

    <!-- ====== 功能模块 ====== -->
    <section class="section-block">
      <h2 class="section-heading">功能模块</h2>
      <div class="module-grid">
        <div
          v-for="mod in modules"
          :key="mod.path"
          class="module-card"
          @click="router.push(mod.path)"
        >
          <div class="mod-icon-box">
            <el-icon size="22"><component :is="mod.icon" /></el-icon>
          </div>
          <div class="mod-body">
            <h3 class="mod-title">{{ mod.title }}</h3>
            <p class="mod-desc">{{ mod.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ====== 快捷操作 ====== -->
    <section class="section-block">
      <h2 class="section-heading">快捷操作</h2>
      <div class="quick-row">
        <div class="quick-card">
          <h3 class="quick-card-heading">开始工作</h3>
          <div class="quick-actions">
            <button class="quick-btn" @click="router.push('/documents')">
              <el-icon size="16"><Upload /></el-icon>
              <span>我的文档</span>
            </button>
            <button class="quick-btn" @click="router.push('/prd/generate')">
              <el-icon size="16"><Document /></el-icon>
              <span>PRD 生成</span>
            </button>
            <button class="quick-btn" @click="router.push('/prototype')">
              <el-icon size="16"><PictureFilled /></el-icon>
              <span>原型生成</span>
            </button>
          </div>
        </div>

        <div class="quick-card">
          <h3 class="quick-card-heading">使用流程</h3>
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
    </section>
  </div>
</template>

<style scoped>
/* ====== 页面整体容器 ====== */
.dashboard {
  max-width: 1120px;
  margin: 0 auto;
  padding: 80px 64px 120px;
  font-family: 'Noto Serif SC', 'Source Serif 4', 'Iowan Old Style', Georgia, 'Times New Roman', serif;
  font-weight: 300;
  color: #2d2a24;
}

/* ====== 欢迎区 ====== */
.welcome-section {
  margin-bottom: 64px;
}
.welcome-inner {
  padding: 56px 0 40px;
  border-bottom: 1px solid rgba(196, 187, 168, 0.4);
}
.welcome-heading {
  margin: 0 0 12px;
  font-size: 28px;
  font-weight: 300;
  letter-spacing: 0.02em;
  color: #2d2a24;
  line-height: 1.3;
}
.welcome-sub {
  margin: 0;
  font-size: 15px;
  color: #8a8a7e;
  font-weight: 300;
  letter-spacing: 0.03em;
}

/* ====== 区段标题 ====== */
.section-block {
  margin-bottom: 56px;
}
.section-heading {
  font-size: 13px;
  font-weight: 400;
  color: #a8a49a;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  margin: 0 0 24px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

/* =================================================================
   功能模块卡片
   ================================================================= */
.module-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: rgba(196, 187, 168, 0.3);
}

.module-card {
  display: flex;
  flex-direction: column;
  padding: 36px 32px;
  background: #faf8f4;
  cursor: pointer;
  transition: background-color 0.7s ease;
  height: 100%;
}
.module-card:hover {
  background: #f0ede6;
}

.mod-icon-box {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-bottom: 20px;
  color: #8a9a7b;
}

.mod-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.mod-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 400;
  color: #2d2a24;
  letter-spacing: 0.02em;
  flex-shrink: 0;
}
.mod-desc {
  margin: 0;
  font-size: 13px;
  color: #8a8a7e;
  line-height: 1.7;
  flex: 1;
  font-weight: 300;
}

/* =================================================================
   快捷操作区
   ================================================================= */
.quick-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1px;
  background: rgba(196, 187, 168, 0.3);
}

.quick-card {
  background: #faf8f4;
  padding: 36px 32px;
  transition: background-color 0.7s ease;
}

.quick-card-heading {
  font-size: 15px;
  font-weight: 400;
  color: #2d2a24;
  margin: 0 0 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(196, 187, 168, 0.4);
  letter-spacing: 0.03em;
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
  border: 1px solid rgba(196, 187, 168, 0.5);
  background: transparent;
  color: #5a564e;
  font-size: 14px;
  font-family: inherit;
  font-weight: 300;
  cursor: pointer;
  transition: background-color 0.7s ease, color 0.7s ease;
  text-align: center;
  line-height: 1;
  padding: 0 12px;
}
.quick-btn :deep(.el-icon) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #8a9a7b;
  transition: color 0.7s ease;
}
.quick-btn:hover {
  background: rgba(138, 154, 123, 0.08);
  color: #2d2a24;
  border-color: rgba(138, 154, 123, 0.3);
}
.quick-btn:active {
  opacity: 0.8;
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
  gap: 16px;
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
  background: #8a9a7b;
  color: #faf8f4;
  font-size: 13px;
  font-weight: 400;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.flow-line {
  width: 1px;
  flex: 1;
  min-height: 24px;
  background: rgba(196, 187, 168, 0.5);
}

.flow-content {
  display: flex;
  flex-direction: column;
  padding-bottom: 24px;
}
.flow-step:last-child .flow-content {
  padding-bottom: 0;
}

.flow-label {
  font-size: 14px;
  font-weight: 400;
  color: #2d2a24;
  line-height: 28px;
  letter-spacing: 0.02em;
}
.flow-desc {
  font-size: 12px;
  color: #8a8a7e;
  line-height: 1.6;
  margin-top: 2px;
  font-weight: 300;
}

/* =================================================================
   响应式
   ================================================================= */
@media (max-width: 1000px) {
  .dashboard {
    padding: 48px 32px 80px;
  }
  .module-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .quick-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .dashboard {
    padding: 32px 20px 64px;
  }
  .module-grid {
    grid-template-columns: 1fr;
  }
  .module-card {
    padding: 28px 24px;
  }
  .quick-card {
    padding: 28px 24px;
  }
  .quick-actions {
    grid-template-columns: 1fr;
  }
  .welcome-heading {
    font-size: 22px;
  }
}
</style>
