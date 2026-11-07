export interface AuthSession {
  userId: number
  username: string
  role: number
  token: string
}

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
    session.token.length > 0
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

export function clearAuthSession(): void {
  window.localStorage.removeItem(STORAGE_KEY)
  window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT))
}

export function getAuthToken(): string | null {
  return readAuthSession()?.token ?? null
}

export function onAuthSessionChanged(listener: () => void): () => void {
  window.addEventListener(AUTH_CHANGED_EVENT, listener)
  window.addEventListener('storage', listener)
  return () => {
    window.removeEventListener(AUTH_CHANGED_EVENT, listener)
    window.removeEventListener('storage', listener)
  }
}
