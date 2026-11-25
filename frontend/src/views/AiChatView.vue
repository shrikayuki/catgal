<script setup lang="ts">
import { gameRoute, readPublicId } from '../utils/navigation'
import { userMessage } from '../utils/user-message'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { MessageCircle, Plus, Send, Settings2, Sparkles, Square, Trash2 } from 'lucide-vue-next'
import {
  createAiSession,
  deleteAiSession,
  getAiBot,
  getAiSession,
  getAiTemplates,
  listAiBots,
  listAiMessages,
  listAiSessions,
  platformAiBot,
  streamAiMessage,
} from '../api/ai'
import type {
  AiBot,
  AiChatRequest,
  AiMessage,
  AiRecommendation,
  AiSession,
  AiTemplate,
} from '../types/ai'
import { latestUserMessageId, validRecommendations } from '../utils/ai-stream'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/types'
import AiSettingsDialog from '../components/ai/AiSettingsDialog.vue'
import AiImage from '../components/ai/AiImage.vue'
import AiMemoryDialog from '../components/ai/AiMemoryDialog.vue'
import AiSummaryDialog from '../components/ai/AiSummaryDialog.vue'
import BaseButton from '../components/ui/BaseButton.vue'

interface PendingRequest extends AiChatRequest {
  sessionId: string
}
const auth = useAuthStore()
const bots = ref<AiBot[]>([]),
  templates = ref<AiTemplate[]>([]),
  sessions = ref<AiSession[]>([]),
  messages = ref<AiMessage[]>([])
const botCursor = ref<string | null>(null),
  sessionCursor = ref<string | null>(null),
  messageCursor = ref<string | null>(null)
const botId = ref<string | null>(null),
  session = ref<AiSession | null>(null),
  showSettings = ref(false),
  showMemory = ref(false),
  showSummary = ref(false)
const loading = ref(false),
  loadingHistory = ref(false),
  busy = ref(false),
  streaming = ref(false),
  error = ref(''),
  status = ref(''),
  draft = ref(''),
  useGameTools = ref(true),
  useInterests = ref(false),
  useSemanticSearch = ref(false)
const pending = ref<PendingRequest | null>(null),
  scrollArea = ref<HTMLElement | null>(null)
const accountValid = ref(true)
const selectedBot = computed(() => bots.value.find((bot) => bot.id === botId.value) ?? null)
const platformBots = computed(() => bots.value.filter((bot) => bot.kind === 'platform'))
const personalBots = computed(() => bots.value.filter((bot) => bot.kind === 'custom'))
const settingsBotId = ref<string | null>(null)
const memoryBotId = computed(() => selectedBot.value?.id ?? session.value?.botId ?? null)
const portraitFailed = ref(false),
  avatarFailed = ref(false)
const latestUserId = computed(() => latestUserMessageId(messages.value))
const activeGeneration = computed(
  () =>
    Boolean(session.value?.activeMessageId) ||
    messages.value.some((row) => row.status === 'running'),
)
let mounted = true,
  selectionEpoch = 0
let readController: AbortController | undefined, streamController: AbortController | undefined
const lifecycle = new AbortController()

function openSettings(create = false) {
  settingsBotId.value = create ? null : botId.value
  showSettings.value = true
}
function explain(cause: unknown) {
  return userMessage(cause instanceof Error ? cause.message : undefined)
}
function recommendations(row: AiMessage): AiRecommendation[] {
  try {
    return validRecommendations(row.recommendations ?? []).filter((game) => readPublicId(game.id) > 0)
  } catch {
    return []
  }
}
function canRegenerate(row: AiMessage) {
  return (
    row.role === 'assistant' && row.replyToId === latestUserId.value && row.status !== 'running'
  )
}
function humanStatus(value: string) {
  return (
    (
      {
        running: '生成中',
        completed: '已完成',
        failed: '生成失败',
        interrupted: '已中断',
      } as Record<string, string>
    )[value] ?? '等待回复'
  )
}
function cancel() {
  streamController?.abort()
}
function resetSelection() {
  selectionEpoch++
  readController?.abort()
  cancel()
  streamController = undefined
  streaming.value = false
  loadingHistory.value = false
  loading.value = false
  messages.value = []
  messageCursor.value = null
  pending.value = null
  session.value = null
  showMemory.value = false
  showSummary.value = false
  useGameTools.value = true
  useInterests.value = false
  useSemanticSearch.value = false
  status.value = ''
  error.value = ''
}
async function scrollBottom() {
  await nextTick()
  if (mounted && scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
}
async function loadBots(more = false) {
  const result = await listAiBots(more ? botCursor.value : null, lifecycle.signal)
  if (!mounted || lifecycle.signal.aborted) return
  const combined = more ? [...bots.value, ...result.items] : result.items
  bots.value = Array.from(new Map(combined.map((bot) => [bot.id, bot])).values())
  botCursor.value = result.nextCursor
}
async function refreshBots() {
  const currentId = botId.value
  try {
    await loadBots()
    if (
      mounted &&
      currentId &&
      currentId === botId.value &&
      !bots.value.some((bot) => bot.id === currentId)
    ) {
      try {
        const current = await getAiBot(currentId, lifecycle.signal)
        if (mounted && botId.value === currentId) bots.value.push(current)
      } catch (cause) {
        if (cause instanceof ApiError && cause.status === 404 && mounted) await chooseBot(null)
        else throw cause
      }
    }
  } catch (cause) {
    if (mounted) error.value = explain(cause)
  }
}
async function moreBots() {
  if (loading.value) return
  loading.value = true
  try {
    await loadBots(true)
  } catch (cause) {
    if (mounted) error.value = explain(cause)
  } finally {
    if (mounted) loading.value = false
  }
}
async function chooseBot(id: string | null) {
  resetSelection()
  botId.value = id
  sessions.value = []
  sessionCursor.value = null
  portraitFailed.value = false
  avatarFailed.value = false
  await loadSessions()
}
async function loadSessions(more = false) {
  const epoch = selectionEpoch
  readController?.abort()
  const request = new AbortController()
  readController = request
  loading.value = true
  try {
    const result = await listAiSessions(
      botId.value,
      more ? sessionCursor.value : null,
      request.signal,
    )
    if (request.signal.aborted || !mounted || epoch !== selectionEpoch) return
    sessions.value = more ? [...sessions.value, ...result.items] : result.items
    sessionCursor.value = result.nextCursor
  } catch (cause) {
    if (!request.signal.aborted && mounted && epoch === selectionEpoch) error.value = explain(cause)
  } finally {
    if (!request.signal.aborted && mounted && epoch === selectionEpoch) loading.value = false
  }
}
async function openSession(row: AiSession) {
  resetSelection()
  session.value = row
  await loadHistory()
  await scrollBottom()
}
async function loadHistory(more = false) {
  const current = session.value
  if (!current || loadingHistory.value || streaming.value) return
  const epoch = selectionEpoch
  const oldHeight = scrollArea.value?.scrollHeight ?? 0
  const oldTop = scrollArea.value?.scrollTop ?? 0
  const request = new AbortController()
  readController?.abort()
  readController = request
  loadingHistory.value = true
  error.value = ''
  try {
    const [result, details] = await Promise.all([
      listAiMessages(current.sessionId, more ? messageCursor.value : null, request.signal),
      getAiSession(current.sessionId, request.signal),
    ])
    if (request.signal.aborted || !mounted || epoch !== selectionEpoch) return
    const added = new Set(result.items.map((item) => item.id))
    messages.value = more
      ? [...result.items, ...messages.value.filter((item) => !added.has(item.id))]
      : result.items
    messageCursor.value = result.nextCursor
    session.value = details
    if (more) {
      await nextTick()
      if (mounted && epoch === selectionEpoch && scrollArea.value)
        scrollArea.value.scrollTop = oldTop + scrollArea.value.scrollHeight - oldHeight
    }
  } catch (cause) {
    if (!request.signal.aborted && mounted && epoch === selectionEpoch) error.value = explain(cause)
  } finally {
    if (mounted && epoch === selectionEpoch) loadingHistory.value = false
  }
}
async function createConversation() {
  if (!botId.value || busy.value || streaming.value) return
  busy.value = true
  error.value = ''
  const chosenId = botId.value
  try {
    const row = await createAiSession(chosenId)
    if (!mounted || chosenId !== botId.value) return
    sessions.value = [row, ...sessions.value]
    await openSession(row)
  } catch (cause) {
    if (mounted) error.value = explain(cause)
  } finally {
    if (mounted) busy.value = false
  }
}
async function usePlatform(template: AiTemplate) {
  if (busy.value || streaming.value) return
  busy.value = true
  error.value = ''
  try {
    const bot = await platformAiBot(template.girlCode)
    if (!mounted) return
    bots.value = [bot, ...bots.value.filter((row) => row.id !== bot.id)]
    await chooseBot(bot.id)
  } catch (cause) {
    if (mounted) error.value = explain(cause)
  } finally {
    if (mounted) busy.value = false
  }
}
async function removeSession(row: AiSession) {
  if (streaming.value || busy.value || !window.confirm(`删除会话「${row.title}」？`)) return
  busy.value = true
  error.value = ''
  try {
    await deleteAiSession(row.sessionId)
    if (!mounted) return
    if (session.value?.sessionId === row.sessionId) resetSelection()
    sessions.value = sessions.value.filter((item) => item.sessionId !== row.sessionId)
  } catch (cause) {
    if (mounted) error.value = explain(cause)
  } finally {
    if (mounted) busy.value = false
  }
}
async function generate(request: PendingRequest) {
  if (
    streaming.value ||
    busy.value ||
    !session.value ||
    session.value.sessionId !== request.sessionId
  )
    return
  const epoch = selectionEpoch
  readController?.abort()
  const controller = new AbortController()
  streamController = controller
  pending.value = request
  streaming.value = true
  error.value = ''
  status.value = '正在连接机器人…'
  let terminal = false,
    assistant: AiMessage | undefined
  try {
    await streamAiMessage(request.sessionId, request, controller.signal, (event) => {
      if (!mounted || epoch !== selectionEpoch) return
      if (event.type === 'meta') {
        // Rebuild from durable IDs on every attempt, including replay, to avoid duplicated text.
        messages.value = messages.value.filter((row) => row.id !== event.messageId)
        if (request.content && !messages.value.some((row) => row.id === event.userMessageId)) {
          messages.value.push({
            id: event.userMessageId!,
            role: 'user',
            content: request.content,
            status: 'completed',
            replyToId: null,
            regeneratedFromId: null,
            clientMessageId: null,
            requestId: null,
            model: null,
            errorCode: null,
            createTime: new Date().toISOString(),
          })
        }
        assistant = {
          id: event.messageId!,
          role: 'assistant',
          content: '',
          status: 'running',
          replyToId: event.userMessageId!,
          regeneratedFromId: request.regenerateMessageId ?? null,
          clientMessageId: request.clientMessageId,
          requestId: event.requestId,
          model: null,
          errorCode: null,
          createTime: new Date().toISOString(),
        }
        messages.value.push(assistant)
        status.value = event.replayed ? '正在读取已保存的回答…' : '正在生成…'
      } else if (event.type === 'delta' && assistant) {
        assistant.content += event.content ?? ''
        messages.value = [...messages.value]
      } else if (event.type === 'recommendations' && assistant) {
        assistant.recommendations = event.items ?? []
        messages.value = [...messages.value]
      } else if (event.type === 'done') {
        terminal = true
        if (assistant) assistant.status = 'completed'
        pending.value = null
        status.value = '回答已保存'
      } else if (event.type === 'error') {
        terminal = true
        if (assistant) {
          assistant.status = 'failed'
          assistant.errorCode = event.code ?? null
        }
        error.value = userMessage(event.message || '这次回答暂未完成，请刷新聊天记录后重试')
        pending.value = new Set([
          'REQUEST_IN_PROGRESS',
          'CONVERSATION_BUSY',
          'PERSISTENCE_UNAVAILABLE',
          'AI_SERVICE_UNAVAILABLE',
          'AI_STREAM_INTERRUPTED',
          'GENERATION_TIMEOUT',
        ]).has(event.code ?? '')
          ? request
          : null
        status.value = ''
      }
      void scrollBottom()
    })
  } catch (cause) {
    if (!mounted || epoch !== selectionEpoch) return
    if (controller.signal.aborted) status.value = '已停止接收，正在核对保存状态'
    else {
      error.value = explain(cause)
      status.value = ''
    }
  } finally {
    if (mounted && epoch === selectionEpoch) {
      streaming.value = false
      streamController = undefined
      // Preserve the stream error while retrieving the authoritative persisted status.
      const streamError = error.value
      await loadHistory()
      if (streamError) error.value = streamError
      await scrollBottom()
      if (!terminal && !error.value) status.value = '已更新聊天记录，可以检查回答或重新生成'
      const saved = messages.value.find((row) => row.clientMessageId === request.clientMessageId)
      if (saved && saved.status !== 'running') pending.value = null
      void loadSessions()
    }
  }
}
async function send() {
  const content = draft.value.trim()
  if (
    !content ||
    content.length > 4000 ||
    !session.value ||
    pending.value ||
    activeGeneration.value
  )
    return
  draft.value = ''
  await generate({
    sessionId: session.value.sessionId,
    clientMessageId: crypto.randomUUID(),
    content,
    useGameTools: useGameTools.value,
    useInterests: useGameTools.value && useInterests.value,
    useSemanticSearch: useGameTools.value && useSemanticSearch.value,
  })
}
async function regenerate(row: AiMessage) {
  if (!session.value || pending.value || activeGeneration.value || !canRegenerate(row)) return
  await generate({
    sessionId: session.value.sessionId,
    clientMessageId: crypto.randomUUID(),
    regenerateMessageId: row.id,
    useGameTools: useGameTools.value,
    useInterests: useGameTools.value && useInterests.value,
    useSemanticSearch: useGameTools.value && useSemanticSearch.value,
  })
}
function editorSelected(bot: AiBot) {
  bots.value = [bot, ...bots.value.filter((row) => row.id !== bot.id)]
  showSettings.value = false
  void chooseBot(bot.id)
}
async function initialize() {
  loading.value = true
  try {
    const [data] = await Promise.all([getAiTemplates(lifecycle.signal), loadBots()])
    if (!mounted) return
    templates.value = data.items
    await chooseBot(bots.value[0]?.id ?? null)
  } catch (cause) {
    if (mounted) error.value = explain(cause)
  } finally {
    if (mounted) loading.value = false
  }
}
function closeRetry() {
  pending.value = null
  void loadHistory()
}
function reloadPage() {
  window.location.reload()
}
watch(useGameTools, (enabled) => {
  if (!enabled) {
    useInterests.value = false
    useSemanticSearch.value = false
  }
})
watch(memoryBotId, () => {
  showMemory.value = false
})
watch(
  () => session.value?.sessionId,
  () => {
    showSummary.value = false
  },
)
watch(
  () => auth.session?.userId,
  () => {
    resetSelection()
    lifecycle.abort()
    mounted = false
    accountValid.value = false
    bots.value = []
    sessions.value = []
    templates.value = []
    showSettings.value = false
  },
)
onBeforeUnmount(() => {
  mounted = false
  lifecycle.abort()
  readController?.abort()
  cancel()
})
void initialize()
</script>

<template>
  <div class="container section workspace-page ai-page">
    <header class="ai-heading">
      <div>
        <p class="ai-eyebrow">CATGAL / A LITTLE COMPANION</p>
        <h1>和喜欢的世界，说说话</h1>
        <p class="work-muted">让助手一起发现作品，也为自己的机器人留一份独特的人设。</p>
      </div>
      <BaseButton
        variant="secondary"
        :disabled="streaming || !accountValid || !session"
        @click="showSummary = true"
        >会话摘要</BaseButton
      >
      <BaseButton
        variant="secondary"
        :disabled="streaming || !accountValid || !memoryBotId"
        @click="showMemory = true"
        >私有记忆</BaseButton
      >
      <BaseButton variant="secondary" :disabled="streaming || !accountValid" @click="openSettings()"
        ><Settings2 :size="17" />管理机器人与 API Key</BaseButton
      >
    </header>
    <div v-if="!accountValid" class="work-card">
      <p>登录账号发生变化，请重新读取自己的聊天记录。</p>
      <BaseButton @click="reloadPage">刷新页面</BaseButton>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <div v-if="accountValid" class="ai-layout">
      <aside class="work-card ai-sidebar">
        <label
          >聊天对象<select
            :value="botId ?? ''"
            :disabled="busy"
            @change="chooseBot(($event.target as HTMLSelectElement).value || null)"
          >
            <option value="">全部会话</option>
            <optgroup v-if="platformBots.length" label="平台助手">
              <option v-for="bot in platformBots" :key="bot.id" :value="bot.id">
                {{ bot.name }}
              </option>
            </optgroup>
            <optgroup v-if="personalBots.length" label="我的个人机器人">
              <option v-for="bot in personalBots" :key="bot.id" :value="bot.id">
                {{ bot.name }}
              </option>
            </optgroup>
          </select></label
        >
        <p v-if="selectedBot" class="work-muted ai-bot-guide">
          {{ selectedBot.kind === 'platform' ? '平台助手' : '个人机器人' }} ·
          {{ selectedBot.credentialId ? '使用我的 API Key' : '平台提供模型' }}
        </p>
        <BaseButton
          size="small"
          variant="secondary"
          :disabled="streaming || busy"
          @click="openSettings(true)"
          ><Plus :size="16" />创建个人机器人</BaseButton
        >
        <BaseButton
          v-if="botCursor"
          size="small"
          variant="ghost"
          :disabled="loading"
          @click="moreBots"
          >加载更多机器人</BaseButton
        >
        <div class="work-toolbar">
          <BaseButton
            size="small"
            :disabled="!botId || busy || streaming"
            @click="createConversation"
            ><Plus :size="16" />新对话</BaseButton
          ><BaseButton
            size="small"
            variant="ghost"
            :disabled="loading || streaming"
            @click="loadSessions()"
            >刷新</BaseButton
          >
        </div>
        <p v-if="loading" class="work-muted" role="status">正在加载会话…</p>
        <nav class="ai-sessions" aria-label="AI 会话">
          <div
            v-for="row in sessions"
            :key="row.sessionId"
            :class="{ selected: session?.sessionId === row.sessionId }"
          >
            <button type="button" :disabled="busy" @click="openSession(row)">
              <strong>{{ row.title }}</strong
              ><small>{{ row.messageCount }} 轮对话</small></button
            ><button
              type="button"
              class="ai-remove"
              aria-label="删除此会话"
              :disabled="busy || streaming"
              @click="removeSession(row)"
            >
              <Trash2 :size="15" />
            </button>
          </div>
        </nav>
        <p v-if="!loading && !sessions.length" class="work-muted">从一段新对话开始。</p>
        <BaseButton
          v-if="sessionCursor"
          size="small"
          variant="ghost"
          :disabled="loading"
          @click="loadSessions(true)"
          >更早的会话</BaseButton
        >
        <section class="ai-templates">
          <h3>使用平台助手</h3>
          <p class="work-muted ai-bot-guide">
            平台提供形象与默认模型，可直接开始。会话和私有记忆按你的账号独立保存。
          </p>
          <button
            v-for="template in templates"
            :key="template.id"
            type="button"
            :disabled="busy || streaming"
            @click="usePlatform(template)"
          >
            <Sparkles :size="16" />{{ template.name }}
          </button>
        </section>
      </aside>
      <main class="work-card ai-conversation">
        <header class="ai-chat-title">
          <span class="ai-avatar"
            ><AiImage
              v-if="selectedBot?.avatarUrl && !avatarFailed"
              :src="selectedBot.avatarUrl"
              alt=""
              referrerpolicy="no-referrer"
              @error="avatarFailed = true" /><Sparkles v-else :size="23"
          /></span>
          <div>
            <h2>
              {{ selectedBot?.name || (session ? '历史对话' : '选择你的聊天伙伴') }}
            </h2>
            <p class="work-muted">
              {{ session?.title || '选择平台助手，或创建自己的机器人' }}
            </p>
            <p v-if="selectedBot" class="ai-source-label">
              {{ selectedBot.kind === 'platform' ? '平台助手' : '个人机器人' }} ·
              {{ selectedBot.credentialId ? '使用我的 API Key' : '平台提供模型' }}
            </p>
          </div>
          <BaseButton
            v-if="session"
            size="small"
            variant="ghost"
            :disabled="streaming || loadingHistory"
            @click="loadHistory()"
            >刷新状态</BaseButton
          >
        </header>
        <div
          ref="scrollArea"
          class="ai-messages"
          role="log"
          aria-label="聊天记录"
          aria-live="polite"
          aria-relevant="additions text"
          :aria-busy="streaming || loadingHistory"
        >
          <div v-if="messageCursor" class="ai-load-older">
            <BaseButton
              size="small"
              variant="ghost"
              :disabled="streaming || loadingHistory"
              @click="loadHistory(true)"
              >读取更早的消息</BaseButton
            >
          </div>
          <p v-if="loadingHistory" class="work-muted" role="status">正在读取聊天记录…</p>
          <div v-if="!messages.length && !loadingHistory" class="ai-welcome">
            <MessageCircle :size="40" />
            <h3>今天想聊什么？</h3>
            <p>聊聊最近喜欢的作品，或请助手一起挑选下一段故事。</p>
            <div v-if="botId && !session">
              <BaseButton :disabled="busy" @click="createConversation">开启新对话</BaseButton>
            </div>
            <p v-else-if="!botId" class="work-muted">
              平台助手可直接使用。想自定义形象与人设，可点击“创建个人机器人”，先添加自己的 API Key。
            </p>
          </div>
          <article v-for="row in messages" :key="row.id" class="ai-message" :class="row.role">
            <div class="ai-message-label">
              {{ row.role === 'user' ? '我' : selectedBot?.name || '助手'
              }}<small v-if="row.role === 'assistant'">{{ humanStatus(row.status) }}</small>
            </div>
            <p class="ai-message-content">
              {{ row.content || (row.status === 'running' ? '正在构思…' : '本次回答未生成内容。') }}
            </p>
            <p v-if="row.errorCode" class="work-muted">这次回答暂未完成，可以重新生成。</p>
            <div v-if="recommendations(row).length" class="ai-recommendations">
              <RouterLink
                v-for="game in recommendations(row)"
                :key="game.id"
                :to="gameRoute(game.id)"
                ><strong>{{ game.name }}</strong
                ><span v-if="game.reason">{{ game.reason }}</span
                ><small>查看作品 →</small></RouterLink
              >
            </div>
            <button
              v-if="canRegenerate(row)"
              type="button"
              class="work-link"
              :disabled="streaming || busy || activeGeneration || !!pending"
              @click="regenerate(row)"
            >
              重新生成这一轮
            </button>
          </article>
        </div>
        <p v-if="status" class="work-muted ai-status" role="status">
          {{ status }}
        </p>
        <div v-if="pending && !streaming" class="ai-pending">
          <p>回答可能已经保存，点击检查结果即可查看最新进度。</p>
          <BaseButton size="small" variant="secondary" @click="generate(pending)"
            >检查回答结果</BaseButton
          ><BaseButton size="small" variant="ghost" @click="closeRetry">关闭重试提示</BaseButton>
        </div>
        <form class="ai-composer" @submit.prevent="send">
          <label class="work-check">
            <input
              v-model="useGameTools"
              type="checkbox"
              :disabled="streaming || !session || !!pending"
            />
            启用游戏查询（需模型支持工具调用）
          </label>
          <p class="work-muted ai-consent-note">关闭后使用普通文本聊天，可用于只支持文本的模型。</p>
          <label class="work-check"
            ><input
              v-model="useInterests"
              type="checkbox"
              :disabled="streaming || !session || !!pending || !useGameTools"
            />使用我的收藏与评价偏好</label
          >
          <p class="work-muted ai-consent-note">
            开启后，本次推荐请求可读取最小兴趣偏好。切换会话后重新选择。
          </p>
          <label class="work-check"
            ><input
              v-model="useSemanticSearch"
              type="checkbox"
              aria-describedby="ai-semantic-search-note"
              :disabled="streaming || !session || !!pending || !useGameTools"
            />语义推荐</label
          >
          <p id="ai-semantic-search-note" class="work-muted ai-consent-note">
            开启后，作品检索词会发送到平台配置的向量模型服务，可能产生额外费用。向量服务与当前聊天模型的
            API Key 独立配置。语义检索失败时会回退普通检索，切换会话后需重新开启。
          </p>
          <label class="sr-only" for="ai-message-input">发送消息</label
          ><textarea
            id="ai-message-input"
            v-model="draft"
            rows="3"
            maxlength="4000"
            :disabled="!session || streaming"
            placeholder="和助手说说你想找的作品，或聊聊今天的心情…"
            @keydown.ctrl.enter.prevent="send"
          />
          <div class="ai-composer-actions">
            <small class="work-muted">{{ draft.length }} / 4000 · Ctrl + Enter 发送</small
            ><BaseButton v-if="streaming" variant="secondary" @click="cancel"
              ><Square :size="15" />停止生成</BaseButton
            ><BaseButton
              v-else
              type="submit"
              :disabled="!session || !draft.trim() || busy || activeGeneration || !!pending"
              ><Send :size="16" />发送</BaseButton
            >
          </div>
          <p v-if="activeGeneration && !streaming" class="work-muted">
            会话仍有生成任务，点击“刷新状态”查看最新结果。
          </p>
        </form>
      </main>
      <aside v-if="selectedBot?.portraitUrl && !portraitFailed" class="ai-portrait">
        <AiImage
          :src="selectedBot.portraitUrl"
          :alt="selectedBot.name + '的立绘'"
          referrerpolicy="no-referrer"
          @error="portraitFailed = true"
        />
        <p>{{ selectedBot.name }}</p>
      </aside>
    </div>
    <AiSettingsDialog
      v-if="showSettings"
      :bots="bots"
      :selected-bot-id="settingsBotId"
      @close="showSettings = false"
      @changed="refreshBots"
      @selected="editorSelected"
    />
    <AiMemoryDialog
      v-if="showMemory && memoryBotId"
      :key="memoryBotId"
      :bot-id="memoryBotId"
      :bot-name="selectedBot?.name ?? '历史聊天伙伴'"
      :read-only="selectedBot?.id !== memoryBotId"
      @close="showMemory = false"
    />
    <AiSummaryDialog
      v-if="showSummary && session"
      :key="session.sessionId"
      :session-id="session.sessionId"
      :session-title="session.title"
      :read-only="!selectedBot || selectedBot.id !== session.botId"
      @close="showSummary = false"
    />
  </div>
</template>

<style scoped>
.ai-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem;
}
.ai-eyebrow {
  color: var(--color-brand-600);
  letter-spacing: 0.16em;
  font-size: var(--text-xs);
}
.ai-layout {
  display: grid;
  grid-template-columns: minmax(13rem, 17rem) minmax(0, 1fr);
  gap: 1.25rem;
  align-items: start;
}
.ai-sidebar {
  position: sticky;
  top: 6rem;
  max-height: calc(100dvh - 7rem);
  overflow: auto;
}
.ai-bot-guide,
.ai-source-label {
  font-size: var(--text-xs);
  line-height: 1.7;
  color: var(--color-text-secondary);
}
.ai-sessions {
  display: grid;
  gap: 0.4rem;
}
.ai-sessions > div {
  display: flex;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
}
.ai-sessions > div.selected {
  border-color: var(--color-brand-300);
  background: var(--color-brand-50);
}
.ai-sessions button {
  min-width: 0;
  padding: 0.75rem;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: var(--color-text-primary);
}
.ai-sessions button:first-child {
  display: grid;
  flex: 1;
  gap: 0.35rem;
}
.ai-sessions strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--text-sm);
}
.ai-sessions small {
  color: var(--color-text-secondary);
}
.ai-sessions .ai-remove {
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.ai-templates {
  margin-top: 1.5rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--color-border);
}
.ai-templates button {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  padding: 0.6rem 0;
  background: transparent;
  border: 0;
  color: var(--color-brand-700);
  cursor: pointer;
}
.ai-conversation {
  padding: 0;
  overflow: hidden;
}
.ai-chat-title {
  display: flex;
  gap: 0.8rem;
  align-items: center;
  padding: 1.1rem 1.4rem;
  border-bottom: 1px solid var(--color-border);
}
.ai-chat-title > div {
  flex: 1;
  min-width: 0;
}
.ai-chat-title h2 {
  font-size: var(--text-lg);
  margin: 0;
}
.ai-chat-title p {
  margin: 0.3rem 0 0;
}
.ai-avatar {
  display: grid;
  place-items: center;
  width: 3rem;
  height: 3rem;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--color-brand-50);
  color: var(--color-brand-600);
  overflow: hidden;
}
.ai-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.ai-messages {
  height: min(56dvh, 42rem);
  min-height: 20rem;
  padding: 1.5rem;
  overflow-y: auto;
  background: linear-gradient(155deg, #f8fbff, #fdfcff);
}
.ai-welcome {
  display: grid;
  justify-items: center;
  align-content: center;
  text-align: center;
  gap: 0.6rem;
  min-height: 19rem;
  color: var(--color-brand-600);
}
.ai-welcome p {
  max-width: 27rem;
  color: var(--color-text-secondary);
  line-height: 1.8;
}
.ai-message {
  width: fit-content;
  max-width: 92%;
  margin-bottom: 1.4rem;
  padding: 1rem 1.2rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 1rem 1rem 1rem 0.25rem;
  box-shadow: var(--shadow-sm);
}
.ai-message.user {
  margin-left: auto;
  background: var(--color-brand-50);
  border-color: var(--color-brand-200);
  border-radius: 1rem 1rem 0.25rem 1rem;
}
.ai-message-label {
  display: flex;
  gap: 0.8rem;
  justify-content: space-between;
  color: var(--color-brand-700);
  font-size: var(--text-xs);
}
.ai-message-label small {
  color: var(--color-text-muted);
}
.ai-message-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
  margin: 0.6rem 0 0;
}
.ai-recommendations {
  display: grid;
  gap: 0.65rem;
  margin-top: 1rem;
}
.ai-recommendations a {
  display: grid;
  gap: 0.4rem;
  padding: 0.9rem;
  border: 1px solid var(--color-brand-200);
  border-radius: var(--radius-md);
  background: var(--color-brand-50);
  text-decoration: none;
  color: var(--color-brand-800);
}
.ai-recommendations span {
  font-size: var(--text-sm);
  line-height: 1.7;
}
.ai-recommendations small {
  color: var(--color-brand-600);
}
.ai-composer {
  padding: 1rem 1.4rem;
  border-top: 1px solid var(--color-border);
}
.ai-composer textarea {
  width: 100%;
}
.ai-composer-actions {
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  align-items: center;
  margin-top: 0.75rem;
}
.ai-consent-note {
  margin: 0.3rem 0 0.8rem;
  font-size: var(--text-xs);
}
.ai-status {
  margin: 0.5rem 1.4rem;
}
.ai-pending {
  padding: 0.8rem 1.4rem;
  background: var(--color-brand-50);
  font-size: var(--text-sm);
}
.ai-pending .base-button {
  margin-right: 0.5rem;
}
.ai-load-older {
  display: flex;
  justify-content: center;
  margin-bottom: 1rem;
}
.ai-portrait {
  display: none;
}
@media (min-width: 1400px) {
  .ai-layout:has(.ai-portrait) {
    grid-template-columns: 16rem minmax(0, 1fr) 12rem;
  }
  .ai-portrait {
    display: block;
    position: sticky;
    top: 7rem;
    text-align: center;
    color: var(--color-text-muted);
  }
  .ai-portrait img {
    width: 100%;
    max-height: 65dvh;
    object-fit: contain;
    filter: drop-shadow(0 8px 20px #b0bfd044);
  }
}
@media (max-width: 760px) {
  .ai-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .ai-sidebar {
    position: static;
    max-height: 19rem;
  }
  .ai-sidebar .ai-templates {
    margin-top: 0.5rem;
  }
  .ai-messages {
    height: 52dvh;
    padding: 1rem;
  }
  .ai-message {
    max-width: 97%;
  }
  .ai-chat-title,
  .ai-composer {
    padding: 1rem;
  }
}
</style>
