import type { AiChatRequest, AiMessage, AiRecommendation, AiStreamEvent } from '../types/ai'

export function aiChatPayload(input: AiChatRequest) {
  return {
    clientMessageId: input.clientMessageId,
    useGameTools: input.useGameTools,
    useInterests: input.useGameTools && input.useInterests,
    ...(input.useGameTools && input.useSemanticSearch === true ? { useSemanticSearch: true } : {}),
    ...(input.content !== undefined ? { content: input.content } : {}),
  }
}

function protocolError(): never {
  throw new Error('AI 响应格式异常，请刷新历史记录确认结果')
}
export function isAiId(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[1-9]\d{0,18}$/.test(value) &&
    (value.length < 19 || value <= '9223372036854775807')
  )
}

export function latestUserMessageId(messages: AiMessage[]): string | undefined {
  let latest: string | undefined
  for (const message of messages) {
    const id = message.role === 'user' ? message.id : message.replyToId
    if (
      isAiId(id) &&
      (!latest || id.length > latest.length || (id.length === latest.length && id > latest))
    )
      latest = id
  }
  return latest
}

export function validRecommendations(value: unknown): AiRecommendation[] {
  if (!Array.isArray(value) || value.length > 5) return protocolError()
  return value.map((item: unknown) => {
    if (!item || typeof item !== 'object') return protocolError()
    const row = item as Record<string, unknown>
    if (
      !isAiId(row.id) ||
      typeof row.name !== 'string' ||
      !row.name.trim() ||
      row.name.length > 200 ||
      (row.reason !== undefined &&
        row.reason !== null &&
        (typeof row.reason !== 'string' || row.reason.length > 500))
    )
      return protocolError()
    return {
      id: row.id,
      name: row.name,
      reason: typeof row.reason === 'string' ? row.reason : undefined,
    }
  })
}

/** Consume complete SSE frames, retaining partial UTF-8 and frame boundaries across reads. */
export async function consumeAiStream(
  body: ReadableStream<Uint8Array>,
  sessionId: string,
  receive: (event: AiStreamEvent) => void,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = '',
    requestId = '',
    hasContent = false,
    terminal = false
  function frame(block: string) {
    if (terminal) return
    let type = '',
      data = ''
    for (const line of block.split(/\r\n|\r|\n/)) {
      if (line.startsWith('event:')) type = line.slice(6).trim()
      if (line.startsWith('data:')) data += `${data ? '\n' : ''}${line.slice(5).replace(/^ /, '')}`
    }
    if (!data && !type) return
    let value: Record<string, unknown>
    try {
      value = JSON.parse(data) as Record<string, unknown>
    } catch {
      return protocolError()
    }
    if (!value || typeof value !== 'object' || Array.isArray(value) || value.type !== type)
      return protocolError()
    if (
      typeof value.requestId !== 'string' ||
      !value.requestId ||
      (requestId && value.requestId !== requestId)
    )
      return protocolError()
    if (type === 'meta') {
      if (
        requestId ||
        value.sessionId !== sessionId ||
        !isAiId(value.messageId) ||
        !isAiId(value.userMessageId) ||
        typeof value.replayed !== 'boolean'
      )
        return protocolError()
      requestId = value.requestId
    } else if (type === 'delta') {
      if (!requestId || typeof value.content !== 'string' || !value.content) return protocolError()
      hasContent = true
    } else if (type === 'done') {
      if (!requestId || !hasContent) return protocolError()
      terminal = true
    } else if (type === 'error') {
      if (typeof value.code !== 'string' || !value.code) return protocolError()
      terminal = true
    } else if (type === 'recommendations') {
      if (!requestId) return protocolError()
      value.items = validRecommendations(value.items)
    } else return protocolError()
    receive(value as unknown as AiStreamEvent)
  }
  try {
    while (!terminal) {
      const chunk = await reader.read()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      if (buffer.length > 524288) return protocolError()
      let match: RegExpExecArray | null
      while ((match = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
        frame(buffer.slice(0, match.index))
        buffer = buffer.slice(match.index + match[0].length)
      }
      if (chunk.done) break
    }
    if (!terminal) throw new Error('AI 响应提前中断，请读取历史记录确认结果后重试')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
