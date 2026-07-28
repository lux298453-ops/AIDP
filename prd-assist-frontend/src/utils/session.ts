const AUTH_STORAGE_KEYS = ['token', 'userId', 'username', 'nickname']

let redirectingToLogin = false

export function clearAuthSession() {
  AUTH_STORAGE_KEYS.forEach(key => localStorage.removeItem(key))
}

export function getStoredToken() {
  return localStorage.getItem('token') || ''
}

export function isJwtExpired(token: string, skewMs = 30_000) {
  if (!token) return true
  try {
    const payload = JSON.parse(decodeBase64Url(token.split('.')[1] || ''))
    const exp = Number(payload.exp)
    if (!Number.isFinite(exp)) return false
    return Date.now() + skewMs >= exp * 1000
  } catch {
    return true
  }
}

export function isStoredTokenValid() {
  const token = getStoredToken()
  return !!token && !isJwtExpired(token)
}

export function handleSessionExpired() {
  clearAuthSession()

  const { pathname, search, hash } = window.location
  if (pathname === '/login' || pathname === '/register' || redirectingToLogin) {
    return false
  }

  redirectingToLogin = true
  const current = `${pathname}${search}${hash}`
  const query = current && current !== '/' ? `?redirect=${encodeURIComponent(current)}` : ''
  window.location.replace(`/login${query}`)
  return true
}

function decodeBase64Url(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
  return atob(padded)
}
