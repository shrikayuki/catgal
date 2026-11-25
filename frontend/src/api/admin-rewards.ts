import { api } from './client'

export interface RewardStatus {
  schemaReady: boolean
  mode: 'LEGACY' | 'DRAINING' | 'ACTIVE'
  baselineCursor?: string
  baselineComplete?: boolean
  baselineDate?: string | null
  blockers?: string[]
  readyToActivate?: boolean
}
export interface RewardEvent {
  id: string
  state: string
  attempts: number
  last_error: string | null
  next_attempt_at: string
  projected_at: string | null
}
export interface RewardOutboxOverview {
  counts: { state: string; count: number }[]
  recent: RewardEvent[]
}
export type RewardAction = 'drain' | 'baseline' | 'activate' | 'cancel-drain'
export function getRewardStatus(signal?: AbortSignal) {
  return api.get<RewardStatus>('/admin/rewards/status', { signal })
}
export function changeRewardMode(action: RewardAction) {
  return api.post<RewardStatus>(`/admin/rewards/${action}`)
}
export function getRewardOutbox(signal?: AbortSignal) {
  return api.get<RewardOutboxOverview>('/admin/rewards/outbox', { signal })
}
export function retryRewardEvent(id: string) {
  return api.post<void>(`/admin/rewards/outbox/${encodeURIComponent(id)}/retry`)
}
