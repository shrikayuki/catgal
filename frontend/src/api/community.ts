import { api } from './client'
import type { PageData } from './types'
import type { GameComment } from '../types/game'

export interface FolderGame {
  gameId: number
  gameName: string
  gameCoverUrl: string | null
}
export interface ReviewInput {
  gameId: number
  recommendLevel: number
  score: number
  playStatus: number
  briefReview: string
  spoilerLevel: number
}
export function createFolder(name: string, description: string, isPublic: boolean) {
  return api.post<void>('/folder', { name, description, isPublic })
}
export function deleteFolder(id: number) {
  return api.delete<void>(`/folder/${id}`)
}
export function getFolderGames(id: number, pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<FolderGame>>(`/folder/${id}?pageNo=${pageNo}&pageSize=12`, { signal })
}
export function addFavorite(folderId: number, gameId: number) {
  return api.post<boolean>('/favorite', { folderId, gameId })
}
export function removeFavorite(folderId: number, gameId: number) {
  return api.delete<boolean>('/favorite', { folderId, gameId })
}
export function addComment(gameId: number, content: string) {
  return api.post<void>('/comment', { gameId, content })
}
export function replyComment(
  gameId: number,
  parentId: number,
  replyCommentId: number,
  content: string,
) {
  return api.post<void>('/comment/reply', { gameId, parentId, replyCommentId, content })
}
export function getThreadReplies(rootId: number, pageNo: number, signal?: AbortSignal) {
  return api.get<PageData<GameComment>>(`/comment/thread/${rootId}/replies?pageNo=${pageNo}&pageSize=10`, {
    signal,
  })
}
export function deleteComment(id: number) {
  return api.delete<void>(`/comment/${id}`)
}
export function addReview(body: ReviewInput) {
  return api.post<void>('/review', body)
}
export function deleteReview(id: number) {
  return api.delete<void>(`/review/${id}`)
}
