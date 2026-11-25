import type { LocationQueryRaw, RouteLocationNormalized, RouteLocationRaw } from 'vue-router'

const referencePrefix = 'c_'

/** 登录与新手引导只接受站内路径作为返回目标。 */
export function internalReturnPath(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(value)) return undefined
  return value
}

// URL-safe presentation encoding. Authorization remains the API's responsibility.
export function encodePublicId(id: number | string): string {
  const numericId = typeof id === 'string' ? Number(id) : id
  if (
    !Number.isSafeInteger(numericId) ||
    numericId <= 0 ||
    (typeof id === 'string' && String(numericId) !== id)
  ) {
    throw new RangeError('Invalid public reference')
  }
  const encoded = btoa(`catgal:1:${id}`)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return referencePrefix + encoded
}

export function readPublicId(value: unknown): number {
  if (typeof value !== 'string') return 0
  let digits = value
  if (value.startsWith(referencePrefix)) {
    const encoded = value.slice(referencePrefix.length)
    if (!/^[A-Za-z0-9_-]+$/.test(encoded)) return 0
    try {
      const decoded = atob(encoded.replace(/-/g, '+').replace(/_/g, '/'))
      const match = /^catgal:1:([1-9][0-9]*)$/.exec(decoded)
      if (!match) return 0
      digits = match[1]!
    } catch {
      return 0
    }
  }
  if (!/^[1-9][0-9]*$/.test(digits)) return 0
  const id = Number(digits)
  if (!Number.isSafeInteger(id) || id <= 0) return 0
  if (value.startsWith(referencePrefix) && encodePublicId(id) !== value) return 0
  return id
}

export function gameRoute(id: number | string, query: LocationQueryRaw = {}, hash = ''): RouteLocationRaw {
  return { name: 'game-detail', params: { gameId: encodePublicId(id) }, query, hash }
}

export function userRoute(id: number): RouteLocationRaw {
  return { name: 'user-profile', params: { userId: encodePublicId(id) } }
}

export function resourceEditRoute(id: number): RouteLocationRaw {
  return { name: 'resource-edit', params: { resourceId: encodePublicId(id) } }
}

export function connectionsRoute(id: number, tab: 'fans' | 'followings'): RouteLocationRaw {
  return { name: 'connections', params: { userId: encodePublicId(id) }, query: { tab } }
}

export function folderRoute(id: number, owner?: number): RouteLocationRaw {
  return {
    name: 'folder-detail',
    params: { folderId: encodePublicId(id) },
    query: owner ? { owner: encodePublicId(owner) } : {},
  }
}

export function directoryRoute(kind: 'companies' | 'tags', id: number): RouteLocationRaw {
  return { name: 'directory', params: { kind, entryId: encodePublicId(id) } }
}

const referenceParams: Record<string, string> = {
  'resource-edit': 'resourceId',
  'game-detail': 'gameId',
  'user-profile': 'userId',
  connections: 'userId',
  'folder-detail': 'folderId',
  directory: 'entryId',
}
const referenceQueries: Record<string, Record<string, string>> = {
  games: { companyId: 'company', tagId: 'tag' },
  'game-detail': { resource: 'resource' },
  'folder-detail': { owner: 'owner' },
  'resource-publish': { gameId: 'game' },
}

/** Canonicalize old bookmarks before loading a page or creating a login redirect. */
export function normalizePublicLocation(
  to: Pick<RouteLocationNormalized, 'name' | 'params' | 'query' | 'hash'>,
): RouteLocationRaw | false | null {
  const name = String(to.name ?? '')
  const params = { ...to.params }
  const query = { ...to.query }
  let changed = false
  const param = referenceParams[name]
  if (param && params[param] != null && params[param] !== '') {
    const id = readPublicId(params[param])
    if (!id) return false
    const encoded = encodePublicId(id)
    changed = params[param] !== encoded
    params[param] = encoded
  }
  for (const [legacyKey, key] of Object.entries(referenceQueries[name] ?? {})) {
    const value = query[key] ?? query[legacyKey]
    if (value === undefined) continue
    const id = readPublicId(value)
    if (!id) return false
    const encoded = encodePublicId(id)
    if (value !== encoded || (legacyKey !== key && legacyKey in query)) changed = true
    if (legacyKey !== key) delete query[legacyKey]
    query[key] = encoded
  }
  return changed ? { name, params, query, hash: to.hash, replace: true } : null
}
