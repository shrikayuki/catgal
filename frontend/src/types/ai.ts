export interface CursorPage<T> {
  items: T[]
  hasMore: boolean
  nextCursor: string | null
}
export interface AiTemplate {
  id: string
  girlCode: string
  name: string
  avatarUrl: string | null
}
export interface AiCredential {
  id: string
  providerCode: string
  label: string
  maskedKey: string
  status: number
  revision: string
  updateTime: string
}
export interface AiBot {
  id: string
  kind: 'platform' | 'custom'
  name: string
  templateId: string
  avatarUrl: string
  portraitUrl: string
  credentialId: string | null
  model: string
  persona?: string
  promptVersion: number
  revision: string
  createTime: string
}
export interface BotInput {
  girlCode?: string
  name: string
  persona: string
  avatarUrl: string
  portraitUrl: string
  credentialId: string | null
  model: string
  revision?: string
}
export interface AiSession {
  id: string
  sessionId: string
  girlCode: string
  botId: string | null
  title: string
  messageCount: number
  memoryVersion: string
  activeMessageId: string | null
  createTime: string
  updateTime: string
}
export interface AiMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: 'running' | 'completed' | 'failed' | 'interrupted'
  replyToId: string | null
  regeneratedFromId: string | null
  clientMessageId: string | null
  requestId: string | null
  model: string | null
  errorCode: string | null
  createTime: string
  recommendations?: AiRecommendation[] | null
}
export interface AiRecommendation {
  id: string
  name: string
  reason?: string
}
export interface AiChatRequest {
  clientMessageId: string
  content?: string
  regenerateMessageId?: string
  useGameTools: boolean
  useInterests: boolean
  useSemanticSearch?: boolean
}
export interface AiStreamEvent {
  type: 'meta' | 'delta' | 'done' | 'error' | 'recommendations'
  requestId: string
  sessionId?: string
  messageId?: string
  userMessageId?: string
  content?: string
  code?: string
  message?: string
  replayed?: boolean
  status?: string
  items?: AiRecommendation[]
}
export interface AiPromptVersion {
  id: string
  version: number
  persona: string
  createTime: string
}
export type AiMemoryType = 'preference' | 'fact'
export interface AiMemory {
  id: string
  botId: string
  content: string
  memoryType: AiMemoryType
  sourceMessageId: string | null
  revision: string
  createTime: string
  updateTime: string
}
export interface AiMemoryInput {
  content: string
  memoryType: AiMemoryType
  sourceMessageId?: string
}
export interface AiMemorySettings {
  botId: string
  memoryEnabled: boolean
  revision: string
}
export interface AiMemoryVectorSettings {
  botId: string
  enabled: boolean
  revision: string
}
export interface AiSessionSummary {
  sessionId: string
  enabled: boolean
  revision: string
  summary: string | null
  status: 'empty' | 'ready' | 'failed'
  coveredTurns: number
  coveredThroughMessageId: string | null
  sourceMemoryVersion: string | null
  lastErrorCode: string | null
  updateTime: string | null
}
export interface AiMemoryCandidate {
  id: string
  botId: string
  content: string
  memoryType: AiMemoryType
  sourceMessageId: string | null
  sourceSessionId: string | null
  revision: string
  createTime: string
}
export interface AiMemoryCandidateSettings {
  botId: string
  enabled: boolean
  revision: string
}
export interface AiMemoryCandidatePage extends CursorPage<AiMemoryCandidate> {
  lastExtraction: { status: string; errorCode: string | null; updateTime: string | null } | null
}
