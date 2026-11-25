import { api } from './client'
import type { CatalogEntry } from '../types/catalog'
import type { PageData } from './types'
export type DirectoryKind = 'companies' | 'tags'
function prefix(kind: DirectoryKind) {
  return kind === 'companies' ? '/company' : '/tag'
}
export function getDirectoryPage(
  kind: DirectoryKind,
  keyword: string,
  pageNo: number,
  signal?: AbortSignal,
) {
  return api.get<PageData<CatalogEntry>>(
    `${prefix(kind)}/page?${new URLSearchParams({ keyword, pageNo: String(pageNo), pageSize: '20' })}`,
    { signal },
  )
}
export function getDirectoryEntry(kind: DirectoryKind, id: number, signal?: AbortSignal) {
  return api.get<CatalogEntry>(`${prefix(kind)}/${id}`, { signal })
}
export function getGameCompanies(gameId: number, signal?: AbortSignal) {
  return api.get<CatalogEntry[]>(`/company/game/${gameId}`, { signal })
}
export function getGameTags(gameId: number, signal?: AbortSignal) {
  return api.get<CatalogEntry[]>(`/game-tag/${gameId}`, { signal })
}
export interface ConnectionUser {
  userId: number
  username: string
  avatarUrl: string | null
  signature: string | null
  isFollow: boolean
}
export function getConnections(
  userId: number,
  kind: 'fans' | 'followings',
  pageNo: number,
  signal?: AbortSignal,
) {
  return api.get<PageData<ConnectionUser>>(
    `/follow/${kind}/page/${userId}?pageNo=${pageNo}&pageSize=20`,
    { signal },
  )
}
