import { api } from './client'
export type SearchKind = 'games' | 'resources'
export interface OutboxStatus {
  pending: number
  failed: number
  oldest_event: string | null
}
export interface RebuildResult {
  index: string
  scannedGames?: number
  scannedResources?: number
}
export interface RedisOverview {
  totalPending: number
  backlogDetected: boolean
  pendingCounts: Record<string, number>
  suspiciousLiteralKeys: Record<string, string>
}
export interface Counters {
  favoriteCount: number | null
  viewCount: number | null
  commentCount: number | null
  reviewCount: number | null
  downloadCount: number | null
  resourceCount: number | null
}
export interface GameConsistency {
  gameId: number
  database: Counters
  redis: Counters
  relationTruth: Counters
  issues: string[]
  consistent: boolean
}
export interface ResourceConsistency {
  resourceId: number
  databaseCount: number | null
  redisCount: number | null
  issues: string[]
  consistent: boolean
}
export function getOutbox(kind: SearchKind, signal?: AbortSignal) {
  return api.get<OutboxStatus>(`/admin/search/${kind}/outbox`, { signal })
}
export function retryOutbox(kind: SearchKind) {
  return api.post<boolean>(`/admin/search/${kind}/retry`)
}
export function rebuildIndex(kind: SearchKind) {
  return api.post<RebuildResult>(`/admin/search/${kind}/rebuild`)
}
export function getRedisOverview(signal?: AbortSignal) {
  return api.get<RedisOverview>('/admin/redis-consistency/overview', { signal })
}
export function inspectGame(id: number) {
  return api.get<GameConsistency>(`/admin/redis-consistency/games/${id}`)
}
export function repairGame(id: number) {
  return api.post<GameConsistency>(`/admin/redis-consistency/games/${id}/repair`)
}
export function inspectResource(id: number) {
  return api.get<ResourceConsistency>(`/admin/redis-consistency/resources/${id}`)
}
export function repairResource(id: number) {
  return api.post<ResourceConsistency>(`/admin/redis-consistency/resources/${id}/repair`)
}
export function syncRedis() {
  return api.post<RedisOverview>('/admin/redis-consistency/sync')
}
