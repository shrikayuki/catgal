import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  clearAuthSession,
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

  function signOut(): void {
    clearAuthSession()
    session.value = null
  }

  return { session, isLoggedIn, isAdmin, setSession, signOut }
})
