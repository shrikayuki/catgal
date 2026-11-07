import type { PageData } from '../api/types'

export interface GameSummary {
  id: number
  name: string
  coverUrl: string | null
  introduction: string | null
  releaseDate: string | null
  rating: number | null
  downloadCount: number | null
  favoriteCount: number | null
  resourceCount: number | null
  commentCount: number | null
  reviewCount: number | null
  viewCount: number | null
  createTime: string | null
  isFavorite: boolean | null
  tags: string[] | null
  companies: string[] | null
  platforms: string[] | null
  languages: string[] | null
  resourceTypes: string[] | null
}

export interface GamePageQuery {
  pageNo?: number
  pageSize?: number
  name?: string
  releaseYear?: number
  resourceType?: string
  language?: string
  platform?: string
  sortBy?: string
  isAsc?: boolean
}

export interface GameResource {
  id: number
  userId: number
  username: string
  avatarUrl: string | null
  resourceCount: number | null
  name: string
  remark: string | null
  types: string[] | null
  languages: string[] | null
  platforms: string[] | null
  likeCount: number | null
  isLike: boolean | null
  isMyResource: boolean | null
  createTime: string | null
}

export interface GameReview {
  id: number
  userId: number
  username: string
  avatarUrl: string | null
  score: number | null
  recommendLevel: number | null
  playStatus: number | null
  spoilerLevel: number | null
  likeCount: number | null
  isLike: boolean | null
  isMyReview: boolean | null
  createTime: string | null
}

export interface GameComment {
  id: number
  userId: number
  username: string
  parentId: number | null
  replyUserId: number | null
  replyCommentId: number | null
  avatarUrl: string | null
  content: string
  likeCount: number | null
  createTime: string | null
  isMyComment: boolean | null
  isLike: boolean | null
}

export interface GameContentEnvelope<T> {
  gameId: number | null
  name: string
  coverUrl: string | null
  rating: number | null
  downloadCount: number | null
  favoriteCount: number | null
  resourceCount: number | null
  commentCount: number | null
  reviewCount: number | null
  viewCount: number | null
  isMyFavorite: boolean | null
  platforms: string[] | null
  languages: string[] | null
  resourceTypes: string[] | null
  page: PageData<T> | null
}

export type GameContentTab = 'resources' | 'reviews' | 'comments'
