import client from './client'
import type { LoginRequest, RegisterRequest, LoginResponse, Result } from '@/types/api'

/**
 * 用户注册。
 * POST /api/auth/register
 */
export function register(data: RegisterRequest) {
  return client.post('/auth/register', data)
}

/**
 * 用户登录，成功后返回 JWT token 和用户信息。
 * POST /api/auth/login
 */
export function login(data: LoginRequest) {
  return client.post<Result<LoginResponse>>('/auth/login', data)
}
