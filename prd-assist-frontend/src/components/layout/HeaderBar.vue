<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Document,
  Edit,
  PictureFilled,
  Checked,
  Folder,
  House,
  SwitchButton,
  DataBoard,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

/** 顶部主导航定义 */
const tabs = [
  { label: '工作台', path: '/', icon: House },
  { label: 'PRD 生成', path: '/prd/generate', icon: Document },
  { label: 'PRD 增强', path: '/prd/enhance', icon: Edit },
  { label: '原型图生成', path: '/prototype', icon: PictureFilled },
  { label: 'PRD 审查', path: '/prd/review', icon: Checked },
  { label: '我的文档', path: '/documents', icon: Folder },
]

/** 当前激活的 tab 路径 */
const activeTab = computed(() => {
  const current = route.path
  if (current === '/') return '/'
  if (current.startsWith('/documents')) return '/documents'
  if (current.startsWith('/prd/generate')) return '/prd/generate'
  if (current.startsWith('/prd/enhance')) return '/prd/enhance'
  if (current.startsWith('/prd/review')) return '/prd/review'
  if (current.startsWith('/prototype')) return '/prototype'
  if (current.startsWith('/prd/')) return '/prd/generate'
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

const userInitial = computed(() => {
  const name = user.nickname || user.username || 'U'
  return name.slice(0, 1).toUpperCase()
})
</script>

<template>
  <header class="header-bar">
    <!-- Logo + 品牌标 -->
    <div class="header-left" @click="goHome">
      <div class="logo-box">
        <el-icon :size="18" color="#ffffff"><DataBoard /></el-icon>
      </div>
      <div class="brand-text">
        <span class="logo-title">AI 文档平台</span>
        <span class="logo-badge">Pro</span>
      </div>
    </div>

    <!-- Tab 导航（居中现代胶囊式） -->
    <nav class="header-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.path"
        type="button"
        class="header-tab"
        :class="{ active: activeTab === tab.path }"
        @click="goTab(tab.path)"
      >
        <el-icon :size="15"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
    </nav>

    <!-- 右侧用户区 -->
    <div class="header-right">
      <div class="user-chip">
        <div class="user-avatar">{{ userInitial }}</div>
        <span class="username" :title="user.nickname || user.username">
          {{ user.nickname || user.username }}
        </span>
      </div>
      <div class="h-divider" />
      <button class="logout-btn" title="退出登录" @click="handleLogout">
        <el-icon :size="14"><SwitchButton /></el-icon>
        <span class="hidden sm:inline">退出</span>
      </button>
    </div>
  </header>
</template>

<style scoped>
.header-bar {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
  gap: 16px;
  z-index: 50;
  position: sticky;
  top: 0;
}

/* Logo */
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  flex-shrink: 0;
  user-select: none;
  padding: 4px 8px 4px 4px;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.header-left:hover {
  background: rgba(241, 245, 249, 0.6);
}
.logo-box {
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background: linear-gradient(180deg, #1e293b 0%, #0f172a 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.18), 0 1px 3px rgba(15, 23, 42, 0.2);
}
.brand-text {
  display: flex;
  align-items: center;
  gap: 6px;
}
.logo-title {
  font-size: 14.5px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: -0.025em;
}
.logo-badge {
  font-size: 10px;
  font-weight: 600;
  color: #2563eb;
  background: #eff6ff;
  border: 1px solid #dbeafe;
  padding: 1px 6px;
  border-radius: 9999px;
  line-height: 1.3;
}

/* Tab 导航（Linear 级嵌入式分段控制器） */
.header-tabs {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: #f1f5f9;
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 10px;
  overflow-x: auto;
  scrollbar-width: none;
}
.header-tabs::-webkit-scrollbar {
  display: none;
}
.header-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  background: transparent;
  border: none;
  transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
  white-space: nowrap;
  user-select: none;
  position: relative;
}
.header-tab .el-icon {
  color: #94a3b8;
  transition: color 0.15s ease;
}
.header-tab:hover {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.5);
}
.header-tab:hover .el-icon {
  color: #475569;
}
.header-tab.active {
  color: #0f172a;
  background: #ffffff;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08), 0 0 0 1px rgba(15, 23, 42, 0.04);
}
.header-tab.active .el-icon {
  color: #2563eb;
}

/* 右侧用户区 */
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 10px 3px 4px;
  border-radius: 9999px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
  cursor: default;
}
.user-avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%);
  color: #ffffff;
  font-size: 10.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25);
}
.username {
  color: #334155;
  font-size: 12.5px;
  font-weight: 500;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.h-divider {
  width: 1px;
  height: 14px;
  background: #e2e8f0;
}
.logout-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 9px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: transparent;
  color: #64748b;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.logout-btn:hover {
  color: #dc2626;
  background: #fef2f2;
  border-color: #fee2e2;
}

@media (max-width: 768px) {
  .header-bar {
    padding: 0 12px;
    gap: 8px;
  }
  .header-tabs {
    flex: 1;
    justify-content: flex-start;
  }
  .header-tab {
    padding: 4px 8px;
    font-size: 12px;
  }
}
</style>
