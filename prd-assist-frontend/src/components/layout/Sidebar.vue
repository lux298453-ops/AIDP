<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

const menus = [
  { label: '工作台', path: '/', icon: 'HomeFilled' },
  { label: '我的文档', path: '/documents', icon: 'Folder' },
  { label: 'PRD 生成', path: '/prd/generate', icon: 'Document' },
  { label: 'PRD 增强', path: '/prd/enhance', icon: 'Edit' },
  { label: 'PRD 审查', path: '/prd/review', icon: 'Checked' },
  { label: '原型图生成', path: '/prototype', icon: 'PictureFilled' },
  // { label: '模型设置', path: '/settings/ai', icon: 'Setting' },
]
</script>

<template>
  <el-menu
    :default-active="route.path"
    class="sidebar"
    background-color="#fff"
    text-color="#606266"
    active-text-color="#409eff"
    @select="(path: string) => router.push(path)"
  >
    <div class="logo">
      <el-icon size="20" color="#409eff"><Monitor /></el-icon>
      <span>AI 文档平台</span>
    </div>
    <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
      <el-icon><component :is="item.icon" /></el-icon>
      <span>{{ item.label }}</span>
    </el-menu-item>
  </el-menu>
</template>

<style scoped>
.sidebar {
  width: 220px; min-height: 100vh;
}
.logo {
  height: 60px; display: flex; align-items: center; justify-content: center; gap: 8px;
  font-size: 16px; font-weight: 700; color: #303133; border-bottom: 1px solid #ebeef5;
}
.el-menu-item.is-active {
  background-color: #ecf5ff !important;
  border-right: 3px solid #409eff;
}
</style>
