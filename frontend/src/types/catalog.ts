export interface CatalogEntry {
  id: number
  name: string
  revision: number
  createTime: string
  updateTime: string
}

export interface AdminGame {
  id: number
  name: string
  coverUrl: string | null
  coverFileId?: string | null
  introduction: string | null
  releaseDate: string | null
  status: number
  revision: number
}

export interface AdminGameDetail {
  game: AdminGame
  tags: CatalogEntry[]
  companies: CatalogEntry[]
}

export interface GameCoverUpdate {
  fileId: string | null
  url: string | null
  revision: number
}

export interface GameCatalogInput {
  name: string
  coverUrl: string | null
  introduction: string | null
  releaseDate: string | null
  tagIds: number[]
  companyIds: number[]
  revision?: number
}

export type ExternalSource = 'bangumi' | 'vndb'
export interface ExternalLink {
  provider: ExternalSource
  externalId: string
  title: string
  url: string
  createTime: string
  updateTime: string
}

export interface ExternalLinkState {
  revision: number
  links: ExternalLink[]
}

export interface ExternalPreview {
  provider: ExternalSource
  externalId: string
  title: string
  originalTitle: string | null
  url: string
  coverUrl: string | null
  description: string | null
  releaseDate: string | null
}

export interface ExternalSearchResult {
  items: ExternalPreview[]
  pageNo: number
  hasMore: boolean
}

export type DictionaryKind = 'companies' | 'tags'
