import type { PageData } from '../api/types'

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload extends LoginPayload {
  phone?: string
  signature?: string
}

export interface LoginResult {
  userId: number
  username: string
  role: number
  token: string
  avatarUrl?: string | null
}

export interface UserProfileUpdate {
  username?: string
  signature?: string
  oldPassword?: string
  newPassword?: string
}

export interface UserComment {
  id: number
  gameId: number
  gameName: string
  content: string
  likeCount: number | null
  createTime: string | null
}

export interface UserReview {
  id: number
  gameId: number
  gameName: string
  recommendLevel: number | null
  playStatus: number | null
  spoilerLevel: number | null
  score: number | null
  briefReview: string | null
  likeCount: number | null
  createTime: string | null
}

export interface UserFavoriteFolder {
  id: number
  name: string
  description: string | null
  isPublic: boolean | null
  gameCount: number | null
  createTime: string | null
}

export interface UserResource {
  revision?: string
  reviewedAt?: string | null
  reviewReason?: string | null
  name?: string | null
  status?: number | null
  id: number
  gameId: number
  gameName: string
  coverUrl: string | null
  types: string[] | null
  languages: string[] | null
  platforms: string[] | null
  createTime: string | null
}

export type UserHomeItem = UserComment | UserReview | UserFavoriteFolder | UserResource

export interface UserHome<T extends UserHomeItem = UserHomeItem> {
  userId: number
  username: string
  avatarUrl: string | null
  signature: string | null
  role: number
  followingCount: number | null
  followerCount: number | null
  commentCount: number | null
  reviewCount: number | null
  folderCount: number | null
  resourceCount: number | null
  createTime: string | null
  cutePoints: number | null
  isFollowing: boolean | null
  showApplyCreator: boolean | null
  pageData: PageData<T> | null
}

export type UserHomeTab = 'comments' | 'reviews' | 'favorites' | 'resources'

export const userHomeTabCodes: Record<UserHomeTab, number> = {
  comments: 1,
  reviews: 2,
  favorites: 3,
  resources: 4,
}
