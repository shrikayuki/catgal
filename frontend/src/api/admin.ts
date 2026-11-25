import { api } from './client'
import type { PageData } from './types'
import type { ResourceHistoryItem } from './resources'

export interface AdminQuery {
  keyword?: string
  pageNo: number
  status?: number
  role?: number
  userId?: number
  gameId?: number
  month?: string
}
function queryString(query: AdminQuery) {
  const params = new URLSearchParams({ pageSize: '20' })
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  return params.toString()
}
export interface Dashboard {
  userCount: number
  creatorCount: number
  adminCount: number
  gameCount: number
  publishedGameCount: number
  offlineGameCount: number
  pendingResourceCount: number
  activeResourceCount: number
  hiddenCommentCount: number
  hiddenReviewCount: number
  currentMonthPrizeCount: number
  drawRecordCount: number
  todayDrawCount: number
  redisAvailable: boolean
  pendingRedisOperations: number
}
export interface AdminUser {
  status: 0 | 1
  storedStatus: number
  banReason: string | null
  bannedUntil: string | null
  banExpired: boolean
  stateVersion: string
  id: number
  username: string
  avatarUrl?: string | null
  role: number
  phone: string | null
  signature: string | null
  createTime: string
}
export type ModerationKind = 'resources' | 'comments' | 'reviews'
export interface ModerationItem {
  id: number
  gameId: number
  gameName: string
  userId: number
  username: string
  avatarUrl?: string | null
  status: number
  createTime: string
  likeCount: number
  name?: string
  content?: string
  score?: number
  spoilerLevel?: number
  downloadUrl?: string
  extractCode?: string
  password?: string
  remark?: string
  fileSize?: string
  types?: string
  languages?: string
  platforms?: string
  downloadCount?: number
  isOfficial?: number
  revision?: string
  reviewedAt?: string | null
  reviewReason?: string | null
}
export interface AdminPrizeInput {
  revision?: string
  prizeName: string
  prizeImage: string | null
  prizeType: number
  month: string
  weight: number
  totalStock: number
  remainStock: number
  status: number
}
export interface AdminPrize extends AdminPrizeInput {
  id: number
  createTime: string
  updateTime: string
}
export function getDashboard(signal?: AbortSignal) {
  return api.get<Dashboard>('/admin/dashboard', { signal })
}
export function getAdminUsers(query: AdminQuery, signal?: AbortSignal) {
  return api.get<PageData<AdminUser>>(`/admin/users?${queryString(query)}`, { signal })
}
export function updateUserRole(id: number, role: number, stateVersion?: string) {
  return api.patch<AdminUser>(`/admin/users/${id}/role`, { role, stateVersion })
}
export function getAdminUser(id: number, signal?: AbortSignal) {
  return api.get<AdminUser>(`/admin/users/${id}`, { signal })
}
export function updateUserStatus(id: number, input: { status: 0 | 1; reason: string; bannedUntil: string | null; stateVersion: string }, signal?: AbortSignal) {
  return api.patch<AdminUser>(`/admin/users/${id}/status`, input, { signal })
}
export interface UserOperationLog {
  id: number
  adminUserId: number
  operation: 'USER_BAN' | 'USER_UNBAN' | 'USER_ROLE_CHANGE'
  targetId: string
  beforeData: Record<string, unknown>
  afterData: Record<string, unknown>
  reason: string | null
  requestId: string | null
  ipAddress: string | null
  createTime: string
}
export interface UserOperationQuery {
  pageNo: number
  adminUserId?: string
  targetUserId?: string
  operation?: string
  requestId?: string
  from?: string
  to?: string
}
export function getUserOperations(query: UserOperationQuery, signal?: AbortSignal) {
  return api.get<PageData<UserOperationLog>>(`/admin/operation-logs?${queryString(query)}`, { signal })
}

export type ContentAuditTarget = 'comment' | 'review'
export type ContentOperation = 'COMMENT_HIDE' | 'COMMENT_RESTORE' | 'REVIEW_HIDE' | 'REVIEW_RESTORE'

export interface ContentOperationLog extends Omit<UserOperationLog, 'operation'> {
  targetType: ContentAuditTarget
  operation: ContentOperation
}

interface ContentOperationQuery extends Omit<UserOperationQuery, 'targetUserId'> {
  pageSize?: number
  targetType?: ContentAuditTarget
  targetId?: string
}

export function getContentOperations(query: ContentOperationQuery, signal?: AbortSignal) {
  return api.get<PageData<ContentOperationLog>>(`/admin/operation-logs/content?${queryString(query)}`, { signal })
}

export type ResourceAuditOperation = 'APPROVE' | 'REJECT' | 'REQUEUE'

export interface ResourceOperationLog extends Omit<UserOperationLog, 'operation' | 'requestId' | 'ipAddress'> {
  targetType: 'resource'
  operation: ResourceAuditOperation
  requestId: null
  ipAddress: null
}

interface ResourceOperationQuery extends Omit<UserOperationQuery, 'targetUserId' | 'requestId'> {
  pageSize?: number
  targetId?: string
}

export function getResourceOperations(query: ResourceOperationQuery, signal?: AbortSignal) {
  return api.get<PageData<ResourceOperationLog>>(`/admin/operation-logs/resources?${queryString(query)}`, { signal })
}

export type PrizeAuditOperation = 'PRIZE_CREATE' | 'PRIZE_UPDATE' | 'PRIZE_ENABLE' | 'PRIZE_DISABLE'

export interface PrizeOperationLog extends Omit<UserOperationLog, 'operation' | 'reason'> {
  operation: PrizeAuditOperation
  targetType: 'prize'
  reason: null
}

interface PrizeOperationQuery extends Omit<UserOperationQuery, 'targetUserId'> {
  pageSize?: number
  targetId?: string
}

export function getPrizeOperations(query: PrizeOperationQuery, signal?: AbortSignal) {
  return api.get<PageData<PrizeOperationLog>>(`/admin/operation-logs/prizes?${queryString(query)}`, { signal })
}

export type RedisAuditOperation = 'GAME_STATS_REPAIR' | 'RESOURCE_DOWNLOAD_REPAIR' | 'COUNTERS_SYNC'
export type RedisAuditOutcome = 'COMPLETED' | 'FAILED' | 'UNCONFIRMED'

export interface RedisOperationLog extends Omit<UserOperationLog, 'operation' | 'targetId' | 'reason'> {
  operation: RedisAuditOperation
  targetType: 'game' | 'resource' | 'global'
  targetId: string | null
  outcome: RedisAuditOutcome
  reason: null
}

interface RedisOperationQuery extends Omit<UserOperationQuery, 'targetUserId'> {
  pageSize?: number
  targetId?: string
  outcome?: RedisAuditOutcome
}

export function getRedisOperations(query: RedisOperationQuery, signal?: AbortSignal) {
  return api.get<PageData<RedisOperationLog>>(`/admin/operation-logs/redis?${queryString(query)}`, { signal })
}

function moderationPath(kind: ModerationKind) {
  return kind === 'resources' ? '/admin/resources' : `/admin/content/${kind}`
}
export function getModeration(kind: ModerationKind, query: AdminQuery, signal?: AbortSignal) {
  return api.get<PageData<ModerationItem>>(`${moderationPath(kind)}?${queryString(query)}`, {
    signal,
  })
}
export function updateModeration(kind: Exclude<ModerationKind, 'resources'>, id: number, status: number) {
  return api.patch<ModerationItem>(`${moderationPath(kind)}/${id}/status`, { status })
}
export function reviewResource(id: number, body: { status: number; revision: string; reason: string }) {
  return api.patch<ModerationItem>(`/admin/resources/${id}/status`, body)
}
export function getAdminResourceHistory(id: number, pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<ResourceHistoryItem>>(`/admin/resources/${id}/history?pageNo=${pageNo}&pageSize=10`, { signal })
}
export function getAdminPrizes(query: AdminQuery, signal?: AbortSignal) {
  return api.get<PageData<AdminPrize>>(`/admin/prizes?${queryString(query)}`, { signal })
}
export function saveAdminPrize(id: number | null, body: AdminPrizeInput) {
  return id === null
    ? api.post<AdminPrize>('/admin/prizes', body)
    : api.put<AdminPrize>(`/admin/prizes/${id}`, body)
}
export function updatePrizeStatus(id: number, status: number, revision?: string) {
  return api.patch<AdminPrize>(`/admin/prizes/${id}/status`, { status }, {
    headers: revision === undefined ? undefined : { 'If-Match': revision },
  })
}
