import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { getUserHome, logout } from '../api/users'
import {
  clearAuthSessionIfCurrent,
  getAuthToken,
  onAuthSessionChanged,
  readAuthSession,
  writeAuthSession,
  type AuthSession,
} from '../services/auth-session'

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(readAuthSession())
  const isLoggedIn = computed(() => session.value !== null)
  const isAdmin = computed(() => session.value?.role === 2)

  const stopListening = onAuthSessionChanged(() => {
    session.value = readAuthSession()
  })
  onScopeDispose(stopListening)

  function setSession(nextSession: AuthSession): void {
    writeAuthSession(nextSession)
    session.value = nextSession
  }

  async function signOut(token: string): Promise<boolean> {
    if (getAuthToken() !== token) return false
    await logout(token)
    return clearAuthSessionIfCurrent(token, 'signed-out')
  }

  function updateSessionUsername(token: string, username: string): boolean {
    const current = readAuthSession()
    if (!current || current.token !== token) return false
    setSession({ ...current, username })
    return true
  }

  function updateSessionAvatar(token: string, avatarUrl: string | null): boolean {
    const current = readAuthSession()
    if (!current || current.token !== token) return false
    setSession({ ...current, avatarUrl })
    return true
  }

  watch(() => session.value?.token, (token, _previous, onCleanup) => {
    // 旧登录记录没有头像字段，只在此时补读资料。换账号或上传新头像后，忽略晚到的旧值。
    if (!token || session.value?.avatarUrl !== undefined) return
    const request = new AbortController()
    onCleanup(() => request.abort())
    void getUserHome({ tab: 1, pageNo: 1, pageSize: 1 }, request.signal)
      .then((profile) => {
        if (!request.signal.aborted && readAuthSession()?.avatarUrl === undefined) {
          updateSessionAvatar(token, profile.avatarUrl ?? null)
        }
      })
      .catch(() => { /* 保留默认头像，资料页仍可按原方式重试。 */ })
  }, { immediate: true })

  return { session, isLoggedIn, isAdmin, setSession, signOut, updateSessionUsername, updateSessionAvatar }
})
