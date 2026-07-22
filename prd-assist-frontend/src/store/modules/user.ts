import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 用户状态管理（Pinia）。
 *
 * 持久化策略：token + 用户信息同时存 Pinia 和 localStorage，
 * 刷新页面后从 localStorage 恢复，保证登录态不丢失。
 */
export const useUserStore = defineStore('user', () => {
  // ==================== 状态 ====================

  const token = ref<string>(localStorage.getItem('token') || '')
  const userId = ref<number>(Number(localStorage.getItem('userId')) || 0)
  const username = ref<string>(localStorage.getItem('username') || '')
  const nickname = ref<string>(localStorage.getItem('nickname') || '')

  // ==================== 计算属性 ====================

  /** 是否已登录 */
  const isLoggedIn = computed(() => !!token.value)

  // ==================== 方法 ====================

  /**
   * 登录成功后调用：保存认证信息到 Pinia + localStorage。
   */
  function setAuth(t: string, uid: number, name: string, nick: string) {
    token.value = t
    userId.value = uid
    username.value = name
    nickname.value = nick

    localStorage.setItem('token', t)
    localStorage.setItem('userId', String(uid))
    localStorage.setItem('username', name)
    localStorage.setItem('nickname', nick)
  }

  /**
   * 登出：清除 Pinia 状态 + localStorage。
   */
  function logout() {
    token.value = ''
    userId.value = 0
    username.value = ''
    nickname.value = ''

    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')
  }

  return { token, userId, username, nickname, isLoggedIn, setAuth, logout }
})
