export interface AuthSession {
  userId: number
  username: string
  role: number
  token: string
  avatarUrl?: string | null
}

export type AuthEndReason = 'account-banned' | 'password-changed' | 'signed-out'

const STORAGE_KEY = 'catgal.auth.session'
const AUTH_CHANGED_EVENT = 'catgal:auth-changed'

function isAuthSession(value: unknown): value is AuthSession {
  if (!value || typeof value !== 'object') return false
  const session = value as Record<string, unknown>
  return (
    typeof session.userId === 'number' &&
    typeof session.username === 'string' &&
    typeof session.role === 'number' &&
    typeof session.token === 'string' &&
    session.token.length > 0 &&
    (session.avatarUrl == null || typeof session.avatarUrl === 'string')
  )
}

export function readAuthSession(): AuthSession | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (isAuthSession(parsed)) return parsed
  } catch {
    // Invalid or unavailable storage is treated as a signed-out session.
  }
  return null
}

export function writeAuthSession(session: AuthSession): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT))
}

export function clearAuthSession(reason?: AuthEndReason): void {
  window.localStorage.removeItem(STORAGE_KEY)
  window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT, { detail: reason }))
}

// 以当前存储的凭据为准，避免旧请求返回时清除另一个标签页刚登录的会话。
export function clearAuthSessionIfCurrent(token: string, reason?: AuthEndReason): boolean {
  if (getAuthToken() !== token) return false
  clearAuthSession(reason)
  return true
}

export function getAuthToken(): string | null {
  return readAuthSession()?.token ?? null
}

export function onAuthSessionChanged(listener: (reason?: AuthEndReason) => void): () => void {
  const receive = (event: Event) => {
    if (event instanceof StorageEvent && event.key !== null && event.key !== STORAGE_KEY) return
    const reason: unknown = event instanceof CustomEvent ? event.detail : undefined
    if (reason === 'account-banned' || reason === 'password-changed' || reason === 'signed-out') listener(reason)
    else listener()
  }
  window.addEventListener(AUTH_CHANGED_EVENT, receive)
  window.addEventListener('storage', receive)
  return () => {
    window.removeEventListener(AUTH_CHANGED_EVENT, receive)
    window.removeEventListener('storage', receive)
  }
}
