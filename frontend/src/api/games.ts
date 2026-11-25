import { api } from './client'
import type { PageData } from './types'
import type {
  GameComment,
  GameContentEnvelope,
  GamePageQuery,
  GameResource,
  GameReview,
  GameSummary,
} from '../types/game'

function buildQuery(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}

export function getGames(query: GamePageQuery, signal?: AbortSignal): Promise<PageData<GameSummary>> {
  return api.get<PageData<GameSummary>>(`/game/page${buildQuery({ ...query })}`, { signal })
}

export function getGame(gameId: number, signal?: AbortSignal): Promise<GameSummary> {
  return api.get<GameSummary>(`/game/${gameId}`, { signal })
}

export function getRandomGame(signal?: AbortSignal): Promise<GameSummary> {
  return api.get<GameSummary>('/game/random', { signal })
}

export function getGameResources(
  gameId: number,
  pageNo: number,
  pageSize: number,
  signal?: AbortSignal,
): Promise<GameContentEnvelope<GameResource>> {
  return api.get<GameContentEnvelope<GameResource>>(
    `/resource/page${buildQuery({ gameId, pageNo, pageSize })}`,
    { signal },
  )
}

export function getGameReviews(
  gameId: number,
  pageNo: number,
  pageSize: number,
  signal?: AbortSignal,
): Promise<GameContentEnvelope<GameReview>> {
  return api.get<GameContentEnvelope<GameReview>>(
    `/review/page${buildQuery({ gameId, pageNo, pageSize })}`,
    { signal },
  )
}

export function getGameComments(
  gameId: number,
  pageNo: number,
  pageSize: number,
  signal?: AbortSignal,
): Promise<GameContentEnvelope<GameComment>> {
  return api.get<GameContentEnvelope<GameComment>>(
    `/comment/page${buildQuery({ gameId, pageNo, pageSize, withReplies: true })}`,
    { signal },
  )
}
