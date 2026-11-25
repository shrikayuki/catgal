export interface LikeState {
  isLike: boolean
  likeCount: number
}

/** The API boolean describes whether this request changed the relationship. */
export function resolveLikeState(count: number | null, nextLiked: boolean, changed: boolean): LikeState {
  let nextCount = Math.max(0, count ?? 0)
  if (changed) nextCount += nextLiked ? 1 : -1
  return { isLike: nextLiked, likeCount: Math.max(0, nextCount) }
}
