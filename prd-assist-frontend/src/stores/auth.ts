import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const username = ref<string>(localStorage.getItem('username') || '')
  const userId = ref<number>(Number(localStorage.getItem('userId')) || 0)

  const isLoggedIn = computed(() => !!token.value)

  function setAuth(t: string, name: string, id: number) {
    token.value = t
    username.value = name
    userId.value = id
    localStorage.setItem('token', t)
    localStorage.setItem('username', name)
    localStorage.setItem('userId', String(id))
  }

  function logout() {
    token.value = ''
    username.value = ''
    userId.value = 0
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('userId')
  }

  return { token, username, userId, isLoggedIn, setAuth, logout }
})
