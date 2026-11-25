/** 每个账号保存一个待确定的逻辑抽奖。存储中只有 UUID，不保存登录凭据。 */
const prefix = 'catgal.draw.pending.v1:'
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** 局域网 HTTP 入口可能没有 randomUUID，使用同一 Web Crypto 随机源生成标准 v4。 */
export function createDrawRequestKey(source: Crypto = crypto): string {
  if (typeof source.randomUUID === 'function') return source.randomUUID()
  const bytes = source.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function readPendingDraw(storage: Pick<Storage, 'getItem'>, userId: number): string | null {
  const value = storage.getItem(`${prefix}${userId}`)
  return value && uuidPattern.test(value) ? value.toLowerCase() : null
}

export function savePendingDraw(storage: Pick<Storage, 'setItem'>, userId: number, key: string): void {
  if (!uuidPattern.test(key)) throw new Error('抽奖请求号格式错误')
  storage.setItem(`${prefix}${userId}`, key.toLowerCase())
}

/** 只清除本次请求，其他标签页写入的新请求号继续保留。 */
export function clearPendingDraw(storage: Pick<Storage, 'getItem' | 'removeItem'>, userId: number, key: string): void {
  if (readPendingDraw(storage, userId) === key.toLowerCase()) storage.removeItem(`${prefix}${userId}`)
}
