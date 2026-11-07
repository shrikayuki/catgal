import { api } from './client'
import type {
  LoginPayload,
  LoginResult,
  RegisterPayload,
  UserHome,
  UserHomeItem,
  UserProfileUpdate,
} from '../types/user'

function buildQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return api.post<LoginResult>('/user/login', payload)
}

export function register(payload: RegisterPayload): Promise<string> {
  return api.post<string>('/user/register', payload)
}

export function getUserHome<T extends UserHomeItem>(params: {
  userId?: number
  tab: number
  pageNo: number
  pageSize?: number
}, signal?: AbortSignal): Promise<UserHome<T>> {
  return api.get<UserHome<T>>(`/user/home${buildQuery(params)}`, { signal })
}

export function getMyPoints(signal?: AbortSignal): Promise<number> {
  return api.get<number>('/user/points', { signal })
}

export function updateProfile(userId: number, payload: UserProfileUpdate): Promise<void> {
  return api.put<void>(`/user/profile/${userId}`, payload)
}

export function updateAvatar(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  return api.post<string>('/user/avatar', form)
}

export function followUser(userId: number): Promise<boolean> {
  return api.post<boolean>(`/follow/${userId}`)
}

export function unfollowUser(userId: number): Promise<boolean> {
  return api.delete<boolean>(`/follow/${userId}`)
}
