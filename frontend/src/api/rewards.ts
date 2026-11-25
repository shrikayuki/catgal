import { api } from './client'
import type { PageData } from './types'

export interface SignRecord {
  signedDays: number[]
  continuousDays: number
}
export interface Prize {
  id: number
  prizeName: string
  prizeImage: string | null
  weight: number | null
  drawable: boolean
  remainStock: number
  totalStock: number
}
export interface DrawResult {
  id: number
  userId: number
  spentCutePoints: number
  requestKey?: string
  bizId?: string
  prizeName?: string
  pointsBalance?: number
  replayed?: boolean
}
export interface DrawRecord {
  id: number
  prizeId: number
  userId: number
  prizeName: string
  prizeImage: string | null
  createTime: string
}
export function getSignRecord(signal?: AbortSignal) {
  return api.get<SignRecord | null>('/sign', { signal })
}
export function signIn() {
  return api.post<void>('/sign')
}
export function getPrizes(signal?: AbortSignal) {
  return api.get<Prize[]>('/prize/new', { signal })
}
export function getDrawCost(signal?: AbortSignal) {
  return api.get<number>('/prize/cost', { signal })
}
export function getRewardMode(signal?: AbortSignal) {
  return api.get<{ mode: 'LEGACY' | 'DRAINING' | 'ACTIVE'; transactional: boolean }>('/prize/mode', { signal })
}
export function drawPrize(requestKey?: string) {
  return api.post<DrawResult>('/prize/draw', undefined, {
    headers: requestKey ? { 'Idempotency-Key': requestKey } : undefined,
  })
}
export function getDrawResult(requestKey: string) {
  return api.get<DrawResult>(`/prize/draw/requests/${encodeURIComponent(requestKey)}`)
}
export function getDrawRecords(pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<DrawRecord>>(`/draw-record/prizes?pageNo=${pageNo}&pageSize=10`, {
    signal,
  })
}
