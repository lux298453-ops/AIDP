<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Setting } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

/** 顶部 Tab 导航 */
const tabs = [
  { label: 'PRD 生成', path: '/prd/generate', icon: 'Document' },
  { label: 'PRD 增强', path: '/prd/enhance', icon: 'Edit' },
  { label: '原型图生成', path: '/prototype', icon: 'PictureFilled' },
  { label: 'PRD 审查', path: '/prd/review', icon: 'Checked' },
]

/** 当前激活的 tab 路径 */
const activeTab = computed(() => {
  // 对子路径做匹配：/prd/generate 和 /prd/enhance 和 /prd/review 都匹配 /prd/*
  for (const tab of tabs) {
    if (route.path.startsWith(tab.path)) return tab.path
  }
  return ''
})

function goTab(path: string) {
  router.push(path)
}

function goHome() {
  router.push('/')
}

function goSettings() {
  router.push('/settings/ai')
}

function handleLogout() {
  user.logout()
  router.push('/login')
}
</script>

<template>
  <div class="header-bar">
    <!-- Logo + 标题 -->
    <div class="header-left" @click="goHome">
      <el-icon size="20" color="#26251e"><Monitor /></el-icon>
      <span class="logo-text">AI 文档平台</span>
    </div>

    <!-- Tab 导航 -->
    <div class="header-tabs">
      <div
        v-for="tab in tabs"
        :key="tab.path"
        class="header-tab"
        :class="{ active: activeTab === tab.path }"
        @click="goTab(tab.path)"
      >
        <el-icon size="14"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </div>
    </div>

    <!-- 右侧用户区 -->
    <div class="header-right">
      <el-tooltip content="模型设置" placement="bottom">
        <button
          class="icon-button"
          :class="{ active: route.path.startsWith('/settings/ai') }"
          type="button"
          aria-label="模型设置"
          @click="goSettings"
        >
          <el-icon size="17"><Setting /></el-icon>
        </button>
      </el-tooltip>
      <span class="username">{{ user.nickname || user.username }}</span>
      <span class="logout-link" @click="handleLogout">退出</span>
    </div>
  </div>
</template>

<style scoped>
.header-bar {
  height: 52px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  background: rgba(242, 241, 237, 0.85);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(38, 37, 30, 0.1);
  flex-shrink: 0;
  gap: 24px;
}

/* Logo */
.header-left {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  flex-shrink: 0;
}
.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: #26251e;
  letter-spacing: -0.28px;
}

/* Tab 导航 */
.header-tabs {
  display: flex;
  align-items: center;
  flex: 1;
  justify-content: center;
  gap: 0;
  height: 100%;
}
.header-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 18px;
  height: 100%;
  font-size: 13px;
  font-weight: 500;
  color: rgba(38, 37, 30, 0.55);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s ease;
  user-select: none;
}
.header-tab:hover {
  color: #f54e00;
}
.header-tab.active {
  color: #26251e;
  border-bottom-color: #26251e;
  font-weight: 600;
}

/* 右侧 */
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.icon-button {
  width: 34px;
  height: 34px;
  border: 1px solid rgba(38, 37, 30, 0.14);
  border-radius: 8px;
  background: #fff;
  color: #26251e;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(38, 37, 30, 0.06);
  transition: color 0.15s, background 0.15s, border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}
.icon-button:hover,
.icon-button.active {
  color: #f54e00;
  border-color: rgba(245, 78, 0, 0.35);
  background: rgba(245, 78, 0, 0.08);
  box-shadow: 0 4px 12px rgba(245, 78, 0, 0.14);
}
.icon-button:hover {
  transform: translateY(-1px);
}
.username {
  color: rgba(38, 37, 30, 0.55);
  font-size: 13px;
}
.logout-link {
  font-size: 13px;
  color: rgba(38, 37, 30, 0.4);
  cursor: pointer;
  transition: color 0.15s;
}
.logout-link:hover {
  color: #cf2d56;
}
</style>
