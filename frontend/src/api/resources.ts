import { api } from './client'
import type { PageData } from './types'

export interface ResourceAccess {
  id: number
  downloadUrl: string | null
  extractCode: string | null
  password: string | null
  fileSize: string | null
}

export function getResourceAccess(id: number, signal?: AbortSignal): Promise<ResourceAccess> {
  return api.get<ResourceAccess>(`/resource/${id}`, { signal })
}

export function requestResourceDownload(id: number, signal?: AbortSignal): Promise<string> {
  return api.post<string>(`/resource/${id}/download`, undefined, { signal })
}

export interface ResourceInput {
  gameId: number
  name: string
  remark: string
  downloadUrl: string
  extractCode: string
  password: string
  fileSize: string
  types: string
  languages: string
  platforms: string
}
export function publishResource(body: ResourceInput) {
  return api.post<void>('/resource', body)
}
export function deleteResource(id: number) {
  return api.delete<void>(`/resource/${id}`)
}

export interface EditableResource extends Omit<ResourceInput, 'remark' | 'extractCode' | 'password'> {
  id: number
  userId: number
  gameName: string | null
  status: number
  revision: string
  remark: string | null
  extractCode: string | null
  password: string | null
  reviewedAt: string | null
  reviewReason: string | null
}

export interface ResourceHistoryItem {
  id: number
  actorName: string | null
  action: string
  oldStatus: number | null
  newStatus: number | null
  resourceName: string | null
  reason: string | null
  changedFields: string | null
  createTime: string | null
}

export function getEditableResource(id: number, signal?: AbortSignal) {
  return api.get<EditableResource>(`/resource/${id}/edit`, { signal })
}

export function editResource(id: number, body: ResourceInput & { revision: string }) {
  return api.put<EditableResource>(`/resource/${id}`, body)
}

export function submitResource(id: number, revision: string) {
  return api.post<EditableResource>(`/resource/${id}/submit`, { revision })
}

export function getResourceHistory(id: number, pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<ResourceHistoryItem>>(`/resource/${id}/history?pageNo=${pageNo}&pageSize=10`, { signal })
}
