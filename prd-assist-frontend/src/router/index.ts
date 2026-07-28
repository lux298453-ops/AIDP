import { createRouter, createWebHistory } from 'vue-router'
import { clearAuthSession, isStoredTokenValid } from '@/utils/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // ==================== 认证页（无需登录） ====================
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: { title: '登录', requiresAuth: false },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/auth/RegisterView.vue'),
      meta: { title: '注册', requiresAuth: false },
    },

    // ==================== 主界面（需要登录） ====================
    {
      path: '/',
      component: () => import('@/components/layout/AppLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'Dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'documents',
          name: 'Documents',
          component: () => import('@/views/documents/DocumentListView.vue'),
          meta: { title: '我的文档' },
        },
        {
          path: 'prd/generate',
          name: 'PrdGenerate',
          component: () => import('@/views/PrdGenerateView.vue'),
          meta: { title: 'PRD生成' },
        },
        {
          path: 'prd/:id',
          name: 'PrdDetail',
          component: () => import('@/views/prd/PrdDetailView.vue'),
          meta: { title: 'PRD详情' },
        },
        {
          path: 'prd/enhance',
          name: 'PrdEnhance',
          component: () => import('@/views/PrdEnhanceView.vue'),
          meta: { title: 'PRD增强' },
        },
        {
          path: 'prd/review',
          name: 'PrdReview',
          component: () => import('@/views/PrdReviewView.vue'),
          meta: { title: 'PRD审查' },
        },
        {
          path: 'prototype',
          name: 'Prototype',
          component: () => import('@/views/PrototypeView.vue'),
          meta: { title: '原型生成' },
        },
        {
          // 从「我的文档」打开指定原型预览
          path: 'prototype/:id(\\d+)',
          name: 'PrototypeDetail',
          component: () => import('@/views/PrototypeView.vue'),
          meta: { title: '原型预览' },
        },
        {
          path: 'settings/ai',
          name: 'AiSettings',
          component: () => import('@/views/settings/AiSettingsView.vue'),
          meta: { title: '模型设置' },
        },
      ],
    },
  ],
})

/**
 * 全局路由守卫 —— 登录拦截。
 *
 * 规则：
 *   1. 访问需要认证的页面但没有 token → 跳转 /login
 *   2. 已登录时访问 /login 或 /register → 直接跳转工作台 /
 *   3. 其余情况放行
 */
router.beforeEach((to, _from, next) => {
  const tokenValid = isStoredTokenValid()

  if (!tokenValid) clearAuthSession()

  // 目标页需要登录但未登录 → 强制跳转登录页
  if (to.meta.requiresAuth && !tokenValid) {
    next('/login')
    return
  }

  // 已登录时访问登录/注册页 → 直接进入工作台
  if ((to.path === '/login' || to.path === '/register') && tokenValid) {
    next('/')
    return
  }

  next()
})

export default router
