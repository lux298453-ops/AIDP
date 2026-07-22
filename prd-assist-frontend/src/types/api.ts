/** 后端统一响应结构 */
export interface Result<T> {
  code: number
  message: string
  data: T
}

// ==================== 认证相关 ====================

/** 注册请求 */
export interface RegisterRequest {
  username: string
  password: string
  nickname: string
}

/** 登录请求 */
export interface LoginRequest {
  username: string
  password: string
}

/** 登录成功响应 */
export interface LoginResponse {
  token: string
  userId: number
  username: string
  nickname: string
}
