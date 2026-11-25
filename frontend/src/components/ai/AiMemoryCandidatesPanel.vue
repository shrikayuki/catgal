<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  confirmAiMemoryCandidate,
  getAiMemoryCandidateSettings,
  listAiMemoryCandidates,
  rejectAiMemoryCandidate,
  saveAiMemoryCandidateSettings,
} from '../../api/ai'
import { ApiError } from '../../api/types'
import type {
  AiMemoryCandidate,
  AiMemoryCandidatePage,
  AiMemoryCandidateSettings,
  AiMemoryType,
} from '../../types/ai'
import { confirmedMemoryInput } from '../../utils/ai-memory'
import { formatDate } from '../../utils/format'
import { useAuthStore } from '../../stores/auth'
import BaseButton from '../ui/BaseButton.vue'

const props = withDefaults(defineProps<{ botId: string; readOnly?: boolean }>(), {
  readOnly: false,
})
const emit = defineEmits<{ saved: []; showSaved: [] }>()
const auth = useAuthStore()
const settings = ref<AiMemoryCandidateSettings | null>(null)
const candidates = ref<AiMemoryCandidate[]>([])
const lastExtraction = ref<AiMemoryCandidatePage['lastExtraction']>(null)
const nextCursor = ref<string | null>(null)
const loading = ref(false),
  busy = ref(false),
  unavailable = ref(false),
  needsReload = ref(false)
const error = ref(''),
  success = ref(''),
  content = ref('')
const editing = ref<AiMemoryCandidate | null>(null),
  memoryType = ref<AiMemoryType>('preference'),
  confirmed = ref(false)
const characterCount = computed(() => Array.from(content.value.trim()).length)
const canConfirm = computed(
  () => !props.readOnly && !unavailable.value && !needsReload.value && !!settings.value,
)
const extractionLabel = computed(() => {
  const status = lastExtraction.value?.status
  if (!status) return '尚无提取记录'
  return (
    (
      {
        idle: '等待后续新消息',
        running: '正在提取建议',
        completed: '最近提取已完成',
        failed: '最近提取失败',
        skipped: '最近一次未产生建议',
      } as Record<string, string>
    )[status] ?? status
  )
})
const controller = new AbortController()
let disposed = false

function clearEditor() {
  editing.value = null
  content.value = ''
  memoryType.value = 'preference'
  confirmed.value = false
}
function clearPrivateState() {
  disposed = true
  controller.abort()
  clearEditor()
  settings.value = null
  candidates.value = []
  lastExtraction.value = null
  error.value = ''
  success.value = ''
}
function handleError(cause: unknown, writing = false) {
  if (disposed || controller.signal.aborted) return
  if (cause instanceof RangeError) {
    needsReload.value = true
    error.value = cause.message
  } else if (cause instanceof ApiError && cause.status === 503) {
    unavailable.value = true
    error.value = '记忆建议服务尚未启用或暂不可用，请稍后刷新或联系管理员。'
  } else if (cause instanceof ApiError && (cause.status === 409 || cause.status === 404)) {
    needsReload.value = true
    confirmed.value = false
    error.value = '这条建议或其来源已更新、处理或失效，请刷新并核对后继续。'
  } else if (
    writing &&
    (!(cause instanceof ApiError) ||
      cause.status === 0 ||
      cause.status >= 500 ||
      cause.status === 200)
  ) {
    needsReload.value = true
    confirmed.value = false
    error.value = '本次操作结果需要核对，请先刷新建议并查看已保存记忆，再决定是否继续。'
  } else error.value = cause instanceof Error ? cause.message : '建议操作失败，请刷新后重试'
}
async function load(more = false) {
  if (disposed || loading.value || busy.value) return
  loading.value = true
  error.value = ''
  try {
    const [page, configuration] = await Promise.all([
      listAiMemoryCandidates(props.botId, more ? nextCursor.value : null, controller.signal),
      more
        ? Promise.resolve(settings.value)
        : getAiMemoryCandidateSettings(props.botId, controller.signal),
    ])
    if (disposed) return
    candidates.value = more ? [...candidates.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    lastExtraction.value = page.lastExtraction
    settings.value = configuration
    unavailable.value = false
    needsReload.value = false
  } catch (cause) {
    handleError(cause)
  } finally {
    if (!disposed) loading.value = false
  }
}
async function reload() {
  if (busy.value || loading.value) return
  if (
    editing.value &&
    content.value.trim() &&
    !window.confirm('刷新会清空尚未提交的建议编辑，是否继续？')
  )
    return
  clearEditor()
  success.value = ''
  await load()
}
async function setEnabled(event: Event) {
  const input = event.target as HTMLInputElement
  const enabled = input.checked
  const current = settings.value
  input.checked = current?.enabled ?? false
  if (
    !current ||
    disposed ||
    busy.value ||
    loading.value ||
    unavailable.value ||
    needsReload.value ||
    (props.readOnly && enabled)
  )
    return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await saveAiMemoryCandidateSettings(
      props.botId,
      current.revision,
      enabled,
      controller.signal,
    )
    if (disposed) return
    settings.value = result
    success.value = result.enabled
      ? '已开启后续新消息的记忆建议提取'
      : '已停止后续提取，现有建议仍可管理'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
function startEdit(row: AiMemoryCandidate) {
  if (!canConfirm.value || busy.value || loading.value) return
  editing.value = row
  content.value = row.content
  memoryType.value = row.memoryType
  confirmed.value = false
  error.value = ''
  success.value = ''
}
async function save() {
  const row = editing.value
  if (!row || disposed || !canConfirm.value || busy.value || loading.value) return
  let input
  try {
    input = confirmedMemoryInput(content.value, memoryType.value, confirmed.value)
  } catch (cause) {
    handleError(cause)
    return
  }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await confirmAiMemoryCandidate(props.botId, row, input, controller.signal)
    if (disposed) return
    candidates.value = candidates.value.filter((candidate) => candidate.id !== row.id)
    clearEditor()
    success.value = result.duplicate
      ? '这条建议已处理，可在已保存记忆中核对'
      : '建议已确认并写入已保存记忆'
    emit('saved')
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
async function reject(row: AiMemoryCandidate) {
  if (disposed || busy.value || loading.value || unavailable.value || needsReload.value) return
  if (!window.confirm('拒绝这条记忆建议并清除候选正文？已有聊天历史会保留。')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await rejectAiMemoryCandidate(props.botId, row, controller.signal)
    if (disposed) return
    candidates.value = candidates.value.filter((candidate) => candidate.id !== row.id)
    if (editing.value?.id === row.id) clearEditor()
    success.value = '建议已拒绝，候选正文已清除'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
watch([() => props.botId, () => auth.session?.userId], clearPrivateState, { flush: 'sync' })
watch(
  () => props.readOnly,
  (value) => {
    if (value) clearEditor()
  },
)
watch(
  [content, memoryType],
  () => {
    confirmed.value = false
  },
  { flush: 'sync' },
)
onBeforeUnmount(clearPrivateState)
void load()
</script>

<template>
  <section aria-label="记忆建议">
    <p class="work-muted">
      这些候选与编辑内容属于当前账号和这个机器人。请核对偏好或事实，避免保存密码、API Key 等凭据。
    </p>
    <p class="work-muted">
      你可以逐条核对、修改后确认，或拒绝建议。确认后的内容会保存为私有记忆，开启“聊天时使用已保存的记忆”后，伙伴会在聊天和推荐中参考这些内容。
    </p>
    <p class="work-muted">
      提取默认关闭。开启后，会使用当前机器人的模型与 API Key
      从后续新用户消息中提取建议，产生额外模型调用费用。
    </p>
    <p v-if="readOnly" class="work-muted">当前为历史机器人，可查看、拒绝建议和关闭后续提取。</p>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="success" class="work-success" role="status">{{ success }}</p>
    <div class="work-toolbar">
      <label class="work-check"
        ><input
          type="checkbox"
          :checked="settings?.enabled ?? false"
          :disabled="
            busy ||
            loading ||
            !settings ||
            unavailable ||
            needsReload ||
            (readOnly && !settings.enabled)
          "
          @change="setEnabled"
        />提取后续消息的记忆建议</label
      >
      <BaseButton size="small" variant="secondary" :disabled="busy || loading" @click="reload"
        >刷新建议</BaseButton
      >
      <BaseButton
        size="small"
        variant="ghost"
        :disabled="busy || loading"
        @click="emit('showSaved')"
        >查看已保存记忆</BaseButton
      >
    </div>
    <div class="ai-candidate-extraction">
      <span class="work-badge">{{ extractionLabel }}</span
      ><small v-if="lastExtraction?.updateTime" class="work-muted">{{
        formatDate(lastExtraction.updateTime)
      }}</small>
      <p v-if="lastExtraction?.status === 'failed'" class="work-muted">
        最近一次建议提取失败，正常聊天可继续。后续新消息后可回来刷新状态。
      </p>
    </div>
    <p v-if="loading" class="work-muted" role="status">正在读取记忆建议…</p>
    <form v-if="editing && !readOnly" class="work-form ai-candidate-editor" @submit.prevent="save">
      <h3>核对这条记忆建议</h3>
      <fieldset :disabled="busy || loading || !canConfirm">
        <label
          >类型<select v-model="memoryType" required>
            <option value="preference">偏好</option>
            <option value="fact">事实</option>
          </select></label
        >
        <label
          >确认保存的内容<textarea v-model="content" required rows="3" maxlength="2000" />
        </label>
        <p class="work-muted" :class="{ 'candidate-over-limit': characterCount > 1000 }">
          {{ characterCount }} / 1000 个字符
        </p>
        <label class="work-check"
          ><input
            v-model="confirmed"
            type="checkbox"
            required
          />确认将当前内容写入这个机器人的已保存记忆</label
        >
        <div class="work-toolbar">
          <BaseButton
            type="submit"
            :loading="busy"
            :disabled="!confirmed || !characterCount || characterCount > 1000"
            >确认保存建议</BaseButton
          ><BaseButton variant="ghost" @click="clearEditor">取消核对</BaseButton>
        </div>
      </fieldset>
    </form>
    <p v-if="!loading && !unavailable && !candidates.length" class="work-muted">
      当前没有待确认的建议。开启提取后，可在后续聊天产生候选时回来核对。
    </p>
    <article v-for="row in candidates" :key="row.id" class="ai-candidate-row">
      <header>
        <span class="work-badge">{{
          row.memoryType === 'preference' ? '偏好建议' : '事实建议'
        }}</span
        ><small class="work-muted">{{ formatDate(row.createTime) }}</small>
      </header>
      <p class="work-text">{{ row.content }}</p>
      <p v-if="row.sourceMessageId || row.sourceSessionId" class="work-muted ai-candidate-source">
        <span>根据你与这位伙伴的聊天整理</span>
      </p>
      <div class="work-toolbar">
        <BaseButton
          v-if="!readOnly"
          size="small"
          variant="secondary"
          :disabled="busy || loading || !canConfirm"
          @click="startEdit(row)"
          >核对并修改</BaseButton
        ><button
          type="button"
          class="work-danger"
          :disabled="busy || loading || unavailable || needsReload"
          @click="reject(row)"
        >
          拒绝并清除
        </button>
      </div>
    </article>
    <BaseButton
      v-if="nextCursor"
      size="small"
      variant="ghost"
      :disabled="busy || loading || unavailable || needsReload"
      @click="load(true)"
      >读取更早的建议</BaseButton
    >
  </section>
</template>

<style scoped>
.ai-candidate-extraction {
  padding: 0.8rem 0;
  display: flex;
  gap: 0.6rem;
  flex-wrap: wrap;
  align-items: center;
}
.ai-candidate-extraction p {
  flex-basis: 100%;
  margin: 0;
}
.ai-candidate-editor {
  padding: 1rem;
  margin: 1rem 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.ai-candidate-editor fieldset {
  display: grid;
  gap: 0.75rem;
  min-width: 0;
  padding: 0;
  margin: 0;
  border: 0;
}
.ai-candidate-editor fieldset > p {
  margin: 0;
}
.ai-candidate-editor .candidate-over-limit {
  color: var(--color-danger);
}
.ai-candidate-row {
  padding: 1rem 0;
  border-bottom: 1px solid var(--color-border);
}
.ai-candidate-row header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.6rem;
}
.ai-candidate-source {
  display: flex;
  gap: 0.7rem;
  flex-wrap: wrap;
  font-size: var(--text-xs);
}
.ai-candidate-source span {
  overflow-wrap: anywhere;
}
.ai-candidate-row .work-toolbar {
  margin-bottom: 0;
}
</style>
