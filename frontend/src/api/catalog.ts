import { api, baseUrl } from './client'
import { clearAuthSession, getAuthToken } from '../services/auth-session'
import type { PageData } from './types'
import type { AdminGame, AdminGameDetail, CatalogEntry, DictionaryKind, ExternalLink, ExternalLinkState,
  ExternalPreview, ExternalSearchResult, ExternalSource, GameCatalogInput, GameCoverUpdate } from '../types/catalog'

export function listAdminGames(keyword: string, pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<AdminGame>>(`/admin/games?${new URLSearchParams({ keyword, pageNo: String(pageNo), pageSize: '12' })}`, { signal })
}
export function getAdminGame(id: number) { return api.get<AdminGameDetail>(`/admin/games/${id}`) }
export function saveGameRelations(id: number, kind: DictionaryKind, ids: number[], revision: number) {
  return api.put<AdminGameDetail>(`/admin/games/${id}/${kind}`, { ids, revision })
}
export function saveAdminGame(id: number | null, body: GameCatalogInput) {
  return id === null ? api.post<AdminGameDetail>('/admin/games', body) : api.put<AdminGameDetail>(`/admin/games/${id}`, body)
}
export function setGameStatus(id: number, status: number) { return api.patch<AdminGame>(`/admin/games/${id}/status`, { status }) }
export function uploadGameCover(id: number, revision: number, file: File, signal?: AbortSignal) {
  const form = new FormData()
  form.append('revision', String(revision))
  form.append('file', file)
  return api.post<GameCoverUpdate>(`/admin/games/${id}/cover`, form, { signal })
}
export function clearGameCover(id: number, revision: number, signal?: AbortSignal) {
  return api.delete<GameCoverUpdate>(`/admin/games/${id}/cover?revision=${revision}`, { signal })
}
export async function getGameCoverImage(fileId: string, signal?: AbortSignal): Promise<Blob> {
  if (!/^[0-9a-f]{32}$/.test(fileId)) throw new Error('封面文件标识无效')
  const token = getAuthToken()
  const response = await fetch(`${baseUrl}/files/game-covers/${fileId}`, {
    signal, credentials: 'same-origin', headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    if (response.status === 401 && token && token === getAuthToken()) clearAuthSession()
    let message = `封面读取失败（HTTP ${response.status}）`
    if (response.headers.get('content-type')?.includes('application/json')) {
      const payload = await response.json() as { msg?: string }
      message = payload.msg || message
    }
    throw new Error(message)
  }
  if (!response.headers.get('content-type')?.startsWith('image/')) throw new Error('封面响应格式无效')
  return response.blob()
}
export function listDictionary(kind: DictionaryKind, keyword: string, pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<CatalogEntry>>(`/admin/${kind}?${new URLSearchParams({ keyword, pageNo: String(pageNo), pageSize: '20' })}`, { signal })
}
export function saveDictionary(kind: DictionaryKind, entry: CatalogEntry | null, name: string) {
  return entry === null ? api.post<CatalogEntry>(`/admin/${kind}`, { name })
    : api.put<CatalogEntry>(`/admin/${kind}/${entry.id}`, { name, revision: entry.revision })
}
export function getDictionaryEntry(kind: DictionaryKind, id: number) {
  return api.get<CatalogEntry>(`/admin/${kind}/${id}`)
}
export function deleteDictionary(kind: DictionaryKind, entry: CatalogEntry) {
  return api.delete<void>(`/admin/${kind}/${entry.id}?revision=${entry.revision}`)
}
export function getExternalLinks(gameId: number) { return api.get<ExternalLinkState>(`/admin/games/${gameId}/external-links`) }
export function getPublicExternalLinks(gameId: number, signal?: AbortSignal) {
  return api.get<ExternalLink[]>(`/game/${gameId}/external-links`, { signal })
}
export function searchExternal(source: ExternalSource, keyword: string, pageNo: number, signal?: AbortSignal) {
  return api.get<ExternalSearchResult>(`/admin/catalog/sources/${source}/search?${new URLSearchParams({ keyword, pageNo: String(pageNo) })}`, { signal })
}
export function previewExternal(source: ExternalSource, id: string, signal?: AbortSignal) {
  return api.get<ExternalPreview>(`/admin/catalog/sources/${source}/items/${encodeURIComponent(id)}`, { signal })
}
export function bindExternal(gameId: number, source: ExternalSource, externalId: string, revision: number) {
  return api.put<ExternalLinkState>(`/admin/games/${gameId}/external-links/${source}`, { externalId, revision })
}
export function unbindExternal(gameId: number, source: ExternalSource, revision: number) {
  return api.delete<ExternalLinkState>(`/admin/games/${gameId}/external-links/${source}?revision=${revision}`)
}
