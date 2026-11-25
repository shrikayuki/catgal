import { api } from './client'

export type LikeTargetType = 'resource' | 'comment' | 'review'

export function likeContent(type: LikeTargetType, targetId: number, signal?: AbortSignal): Promise<boolean> {
  return api.post<boolean>('/like', { type, targetId }, { signal })
}

export function unlikeContent(type: LikeTargetType, targetId: number, signal?: AbortSignal): Promise<boolean> {
  return api.delete<boolean>('/like', { type, targetId }, { signal })
}
