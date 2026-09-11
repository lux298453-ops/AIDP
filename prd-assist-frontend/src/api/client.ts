import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getStoredToken, handleSessionExpired, isJwtExpired } from '@/utils/session'

const apiBase = import.meta.env.VITE_API_BASE_URL
  ? String(import.meta.env.VITE_API_BASE_URL).replace(/\/+$/, '')
  : ''

const client = axios.create({
  baseURL: `${apiBase}/api`,
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 请求拦截器：附加 JWT
client.interceptors.request.use((config) => {
  const token = getStoredToken()
  if (token) {
    if (isJwtExpired(token)) {
      handleSessionExpired()
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一错误处理
client.interceptors.response.use(
  (response) => {
    // blob 响应（文件下载）跳过 code 检查
    if (response.config.responseType === 'blob') return response
    const data = response.data
    if (data?.code === 401) {
      if (handleSessionExpired()) ElMessage.warning(data.message || '登录已过期，请重新登录')
      return Promise.reject(new Error(data.message || '登录已过期，请重新登录'))
    }
    if (data.code !== 200) {
      if ((response.config as any)?.silentError) {
        return Promise.reject(new Error(data.message))
      }
      ElMessage.error(data.message || '请求失败')
      return Promise.reject(new Error(data.message))
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      if (handleSessionExpired()) {
        ElMessage.warning(error.response?.data?.message || '登录已过期，请重新登录')
      }
      return Promise.reject(error)
    }
    if (!(error.config as any)?.silentError) {
      ElMessage.error(error.response?.data?.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default client
