import { api, baseUrl, responseError } from './client'
import { privateAiImagePath } from '../utils/image-upload'
import { getAuthToken } from '../services/auth-session'
import { aiChatPayload, consumeAiStream } from '../utils/ai-stream'
import { summaryRevision } from '../utils/ai-summary'
import { memoryCandidateRevision } from '../utils/ai-memory-candidates'
import { memoryVectorRevision, parseMemoryVectorSettings } from '../utils/ai-memory-vector'
import type {
  AiBot,
  AiChatRequest,
  AiCredential,
  AiMessage,
  AiMemory,
  AiMemoryInput,
  AiMemorySettings,
  AiMemoryCandidate,
  AiMemoryCandidatePage,
  AiMemoryCandidateSettings,
  AiPromptVersion,
  AiSession,
  AiSessionSummary,
  AiStreamEvent,
  AiTemplate,
  BotInput,
  CursorPage,
} from '../types/ai'

function cursor(beforeId?: string | null, extra: Record<string, string> = {}) {
  const query = new URLSearchParams({ limit: '20', ...extra })
  if (beforeId) query.set('beforeId', beforeId)
  return query
}
export const getAiTemplates = (signal?: AbortSignal) =>
  api.get<{ items: AiTemplate[] }>('/ai/templates', { signal })
export const getAiProviders = (signal?: AbortSignal) =>
  api.get<{ items: { code: string }[] }>('/ai/providers', { signal })
export const listAiBots = (before?: string | null, signal?: AbortSignal) =>
  api.get<CursorPage<AiBot>>(`/ai/bots?${cursor(before)}`, { signal })
export const getAiBot = (id: string, signal?: AbortSignal) =>
  api.get<AiBot>(`/ai/bots/${id}`, { signal })
export const platformAiBot = (code: string) =>
  api.post<AiBot>(`/ai/bots/platform/${encodeURIComponent(code)}`)
export const saveAiBot = (id: string | null, input: BotInput, signal?: AbortSignal) =>
  id ? api.put<AiBot>(`/ai/bots/${id}`, input, { signal }) : api.post<AiBot>('/ai/bots', input, { signal })

export type AiImageKind = 'avatar' | 'portrait'
export interface AiImageUpdate { url: string; revision: string }

export function uploadAiImage(id: string, kind: AiImageKind, revision: string, file: File, signal?: AbortSignal) {
  const form = new FormData()
  form.append('revision', revision)
  form.append('file', file)
  return api.post<AiImageUpdate>(`/ai/bots/${id}/images/${kind}`, form, { signal })
}

export function clearAiImage(id: string, kind: AiImageKind, revision: string, signal?: AbortSignal) {
  const query = new URLSearchParams({ revision })
  return api.delete<AiImageUpdate>(`/ai/bots/${id}/images/${kind}?${query}`, undefined, { signal })
}

export async function getAiImage(src: string, signal: AbortSignal): Promise<Blob> {
  const path = privateAiImagePath(src)
  if (!path) throw new Error('这张图片暂时无法显示')
  const token = getAuthToken()
  const response = await fetch(`${baseUrl}${path}`, {
    signal,
    credentials: 'same-origin',
    cache: 'no-store',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) throw await responseError(response, token)
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(response.headers.get('content-type') ?? '')) {
    throw new Error('这张图片暂时无法显示')
  }
  return response.blob()
}
export const deleteAiBot = (bot: AiBot) =>
  api.delete<{ deleted: boolean }>(`/ai/bots/${bot.id}?revision=${bot.revision}`)
export const listAiPrompts = (id: string, before?: string | null) =>
  api.get<CursorPage<AiPromptVersion>>(`/ai/bots/${id}/prompt-versions?${cursor(before)}`)
export const listAiCredentials = (before?: string | null, signal?: AbortSignal) =>
  api.get<CursorPage<AiCredential>>(`/ai/credentials?${cursor(before)}`, {
    signal,
  })
export const createAiCredential = (providerCode: string, label: string, apiKey: string) =>
  api.post<AiCredential>('/ai/credentials', { providerCode, label, apiKey })
export const rotateAiCredential = (row: AiCredential, apiKey: string) =>
  api.put<AiCredential>(`/ai/credentials/${row.id}/key`, {
    revision: row.revision,
    apiKey,
  })
export const revokeAiCredential = (row: AiCredential) =>
  api.delete<AiCredential>(`/ai/credentials/${row.id}?revision=${row.revision}`)
export const listAiSessions = (
  botId: string | null,
  before?: string | null,
  signal?: AbortSignal,
) =>
  api.get<CursorPage<AiSession>>(`/ai/sessions?${cursor(before, botId ? { botId } : {})}`, {
    signal,
  })
export const createAiSession = (botId: string) => api.post<AiSession>('/ai/sessions', { botId })
export const getAiSession = (id: string, signal?: AbortSignal) =>
  api.get<AiSession>(`/ai/sessions/${id}`, { signal })
export const deleteAiSession = (id: string) =>
  api.delete<{ deleted: boolean }>(`/ai/sessions/${id}`)
export const listAiMessages = (id: string, before?: string | null, signal?: AbortSignal) =>
  api.get<CursorPage<AiMessage>>(`/ai/sessions/${id}/messages?${cursor(before)}`, { signal })

export const getAiMemorySettings = (botId: string, signal?: AbortSignal) =>
  api.get<AiMemorySettings>(`/ai/bots/${botId}/memory-settings`, { signal })
export const getAiMemoryVectorSettings = (botId: string, signal?: AbortSignal) =>
  api
    .get<unknown>(`/ai/bots/${botId}/memory-vector-settings`, { signal })
    .then((value) => parseMemoryVectorSettings(value, botId))
export const saveAiMemoryVectorSettings = (
  botId: string,
  revision: string,
  enabled: boolean,
  signal?: AbortSignal,
) =>
  api
    .put<unknown>(
      `/ai/bots/${botId}/memory-vector-settings`,
      { revision: memoryVectorRevision(revision), enabled },
      { signal },
    )
    .then((value) => parseMemoryVectorSettings(value, botId))
export const saveAiMemorySettings = (
  botId: string,
  settings: Pick<AiMemorySettings, 'revision' | 'memoryEnabled'>,
  signal?: AbortSignal,
) => api.put<AiMemorySettings>(`/ai/bots/${botId}/memory-settings`, settings, { signal })
export const listAiMemories = (botId: string, before?: string | null, signal?: AbortSignal) =>
  api.get<CursorPage<AiMemory>>(`/ai/bots/${botId}/memories?${cursor(before)}`, { signal })
export const createAiMemory = (botId: string, input: AiMemoryInput, signal?: AbortSignal) =>
  api.post<AiMemory>(`/ai/bots/${botId}/memories`, input, { signal })
export const updateAiMemory = (
  botId: string,
  row: AiMemory,
  input: Pick<AiMemoryInput, 'content' | 'memoryType'>,
  signal?: AbortSignal,
) =>
  api.put<AiMemory>(
    `/ai/bots/${botId}/memories/${row.id}`,
    { ...input, revision: row.revision },
    { signal },
  )
export const deleteAiMemory = (botId: string, row: AiMemory, signal?: AbortSignal) =>
  api.delete<void>(`/ai/bots/${botId}/memories/${row.id}?revision=${row.revision}`, undefined, {
    signal,
  })

export const getAiSummary = (sessionId: string, signal?: AbortSignal) =>
  api.get<AiSessionSummary>(`/ai/sessions/${sessionId}/summary`, { signal })
export const saveAiSummary = (
  sessionId: string,
  revision: string,
  enabled: boolean,
  signal?: AbortSignal,
) =>
  api.put<AiSessionSummary>(
    `/ai/sessions/${sessionId}/summary`,
    { revision: summaryRevision(revision), enabled },
    { signal },
  )
export const clearAiSummary = (sessionId: string, revision: string, signal?: AbortSignal) =>
  api.delete<AiSessionSummary>(
    `/ai/sessions/${sessionId}/summary?revision=${summaryRevision(revision)}`,
    undefined,
    { signal },
  )

export const getAiMemoryCandidateSettings = (botId: string, signal?: AbortSignal) =>
  api.get<AiMemoryCandidateSettings>(`/ai/bots/${botId}/memory-candidate-settings`, { signal })
export const saveAiMemoryCandidateSettings = (
  botId: string,
  revision: string,
  enabled: boolean,
  signal?: AbortSignal,
) =>
  api.put<AiMemoryCandidateSettings>(
    `/ai/bots/${botId}/memory-candidate-settings`,
    { revision: memoryCandidateRevision(revision, true), enabled },
    { signal },
  )
export const listAiMemoryCandidates = (
  botId: string,
  before?: string | null,
  signal?: AbortSignal,
) =>
  api.get<AiMemoryCandidatePage>(`/ai/bots/${botId}/memory-candidates?${cursor(before)}`, {
    signal,
  })
export const confirmAiMemoryCandidate = (
  botId: string,
  candidate: AiMemoryCandidate,
  input: Pick<AiMemoryInput, 'content' | 'memoryType'>,
  signal?: AbortSignal,
) =>
  api.post<{ memoryId: string; duplicate: boolean }>(
    `/ai/bots/${botId}/memory-candidates/${candidate.id}/confirm`,
    {
      revision: memoryCandidateRevision(candidate.revision),
      content: input.content,
      memoryType: input.memoryType,
    },
    { signal },
  )
export const rejectAiMemoryCandidate = (
  botId: string,
  candidate: AiMemoryCandidate,
  signal?: AbortSignal,
) =>
  api.post<{ rejected: boolean }>(
    `/ai/bots/${botId}/memory-candidates/${candidate.id}/reject`,
    { revision: memoryCandidateRevision(candidate.revision) },
    { signal },
  )

export async function streamAiMessage(
  sessionId: string,
  input: AiChatRequest,
  signal: AbortSignal,
  receive: (event: AiStreamEvent) => void,
) {
  const token = getAuthToken()
  const path = input.regenerateMessageId
    ? `/ai/sessions/${sessionId}/messages/${input.regenerateMessageId}/regenerate`
    : `/ai/sessions/${sessionId}/messages`
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    credentials: 'same-origin',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(aiChatPayload(input)),
  })
  if (!response.ok) throw await responseError(response, token)
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body)
    throw new Error('暂时无法取得回答，请稍后重试')
  await consumeAiStream(response.body, sessionId, receive)
}
