<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
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

function handleLogout() {
  user.logout()
  router.push('/login')
}
</script>

<template>
  <div class="header-bar">
    <!-- Logo + 标题 -->
    <div class="header-left" @click="goHome">
      <el-icon size="20" color="#409eff"><Monitor /></el-icon>
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
      <span class="username">{{ user.nickname || user.username }}</span>
      <el-button type="text" @click="handleLogout">退出</el-button>
    </div>
  </div>
</template>

<style scoped>
.header-bar {
  height: 52px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
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
  color: #303133;
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
  color: #606266;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  user-select: none;
}
.header-tab:hover {
  color: #409eff;
}
.header-tab.active {
  color: #409eff;
  border-bottom-color: #409eff;
  font-weight: 500;
}

/* 右侧 */
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.username {
  color: #606266;
  font-size: 13px;
}
</style>
