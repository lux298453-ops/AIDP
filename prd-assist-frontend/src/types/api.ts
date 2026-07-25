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

export interface AiModelConfig {
  id?: number
  provider: string
  baseUrl: string
  maskedApiKey?: string
  hasApiKey?: boolean
  model: string
  apiType: string
  appendApiPath: boolean
  openAiAuthEnabled: boolean
  authHeaderType?: string
  actorAuthorization?: string
  reasoningEffort?: string
  disableResponseStorage: boolean
  maxOutputTokens: number
  imageDetail: string
  enabled: boolean
}

export interface AiModelConfigRequest {
  provider: string
  baseUrl: string
  apiKey?: string
  model: string
  apiType: string
  appendApiPath: boolean
  openAiAuthEnabled: boolean
  authHeaderType: string
  actorAuthorization?: string
  reasoningEffort?: string
  disableResponseStorage: boolean
  maxOutputTokens: number
  imageDetail: string
  enabled: boolean
}
