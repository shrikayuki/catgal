<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  createAiMemory,
  deleteAiMemory,
  getAiMemorySettings,
  listAiMemories,
  saveAiMemorySettings,
  updateAiMemory,
} from '../../api/ai'
import { ApiError } from '../../api/types'
import type { AiMemory, AiMemorySettings, AiMemoryType } from '../../types/ai'
import { confirmedMemoryInput } from '../../utils/ai-memory'
import { formatDate } from '../../utils/format'
import { useAuthStore } from '../../stores/auth'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'
import AiMemoryCandidatesPanel from './AiMemoryCandidatesPanel.vue'
import AiMemoryVectorSettings from './AiMemoryVectorSettings.vue'

const props = withDefaults(defineProps<{ botId: string; botName: string; readOnly?: boolean }>(), {
  readOnly: false,
})
const emit = defineEmits<{ close: [] }>()
const auth = useAuthStore()
const tab = ref<'saved' | 'candidates'>('saved')
const savedUpdated = ref(false)
const settings = ref<AiMemorySettings | null>(null)
const memories = ref<AiMemory[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false),
  busy = ref(false),
  unavailable = ref(false),
  conflict = ref(false)
const error = ref(''),
  success = ref(''),
  content = ref('')
const memoryType = ref<AiMemoryType>('preference'),
  confirmed = ref(false)
const editing = ref<AiMemory | null>(null)
const characterCount = computed(() => Array.from(content.value.trim()).length)
const canWrite = computed(
  () => !props.readOnly && !unavailable.value && !conflict.value && !!settings.value,
)
const controller = new AbortController()
let disposed = false

function clearEditor() {
  content.value = ''
  memoryType.value = 'preference'
  editing.value = null
  confirmed.value = false
}
function clearPrivateState() {
  disposed = true
  controller.abort()
  clearEditor()
  memories.value = []
  settings.value = null
  nextCursor.value = null
  error.value = ''
  success.value = ''
}
function handleError(cause: unknown, writing = false) {
  if (disposed || controller.signal.aborted) return
  if (cause instanceof ApiError && cause.status === 503) {
    unavailable.value = true
    error.value = '长期记忆暂时无法使用，请稍后重新加载。'
  } else if (cause instanceof ApiError && cause.status === 409) {
    conflict.value = true
    error.value = '记录已在其他窗口更新，请重新加载并核对内容后再次提交。'
  } else if (
    writing &&
    (!(cause instanceof ApiError) ||
      cause.status === 0 ||
      cause.status >= 500 ||
      cause.status === 200)
  ) {
    conflict.value = true
    confirmed.value = false
    error.value = '本次提交结果需要核对，请重新加载已保存内容，再决定是否继续提交。'
  } else error.value = cause instanceof Error ? cause.message : '记忆操作失败，请重新加载后重试'
}
async function load(more = false) {
  if (disposed || loading.value || busy.value) return
  loading.value = true
  error.value = ''
  try {
    const [page, configuration] = await Promise.all([
      listAiMemories(props.botId, more ? nextCursor.value : null, controller.signal),
      more ? Promise.resolve(settings.value) : getAiMemorySettings(props.botId, controller.signal),
    ])
    if (disposed) return
    memories.value = more ? [...memories.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    settings.value = configuration
    unavailable.value = false
    conflict.value = false
    if (!more) savedUpdated.value = false
  } catch (cause) {
    handleError(cause)
  } finally {
    if (!disposed) loading.value = false
  }
}
async function reload() {
  if (loading.value || busy.value) return
  if (content.value.trim() && !window.confirm('重新加载会清空当前尚未提交的编辑，是否继续？'))
    return
  clearEditor()
  success.value = ''
  await load()
}
async function selectTab(next: 'saved' | 'candidates') {
  if (disposed || loading.value || busy.value || tab.value === next) return
  if (content.value.trim() && !window.confirm('切换分类会清空尚未提交的记忆编辑，是否继续？'))
    return
  clearEditor()
  tab.value = next
  if (next === 'saved') {
    success.value = ''
    await load()
  }
}
async function setEnabled(event: Event) {
  const input = event.target as HTMLInputElement
  const configuration = settings.value
  const enabled = input.checked
  input.checked = configuration?.memoryEnabled ?? false
  if (
    !configuration ||
    disposed ||
    busy.value ||
    loading.value ||
    conflict.value ||
    unavailable.value ||
    (props.readOnly && enabled)
  )
    return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await saveAiMemorySettings(
      props.botId,
      { revision: configuration.revision, memoryEnabled: enabled },
      controller.signal,
    )
    if (disposed) return
    settings.value = result
    success.value = result.memoryEnabled
      ? '已开启这个机器人的私有记忆召回'
      : '已关闭后续记忆召回，保存的内容仍可查看和删除'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
function startEdit(row: AiMemory) {
  if (!canWrite.value || busy.value || loading.value) return
  editing.value = row
  content.value = row.content
  memoryType.value = row.memoryType
  confirmed.value = false
  error.value = ''
  success.value = ''
}
async function save() {
  if (disposed || !canWrite.value || busy.value || loading.value) return
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
    const row = editing.value
      ? await updateAiMemory(props.botId, editing.value, input, controller.signal)
      : await createAiMemory(props.botId, input, controller.signal)
    if (disposed) return
    memories.value = editing.value
      ? memories.value.map((previous) => (previous.id === row.id ? row : previous))
      : [row, ...memories.value]
    clearEditor()
    success.value = '私有记忆已保存'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
async function remove(row: AiMemory) {
  if (disposed || busy.value || loading.value || unavailable.value || conflict.value) return
  if (!window.confirm('删除这条私有记忆？保存的正文将清除，已有聊天历史会保留。')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await deleteAiMemory(props.botId, row, controller.signal)
    if (disposed) return
    memories.value = memories.value.filter((previous) => previous.id !== row.id)
    if (editing.value?.id === row.id) clearEditor()
    success.value = '记忆已删除，已有聊天历史保留'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
watch([() => props.botId, () => auth.session?.userId], () => {
  clearPrivateState()
  emit('close')
})
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
  <ActionDialog :title="`${botName}的私有记忆`" @close="emit('close')">
    <nav class="work-toolbar" aria-label="私有记忆分类">
      <BaseButton
        :variant="tab === 'saved' ? 'primary' : 'ghost'"
        :disabled="loading || busy"
        @click="selectTab('saved')"
        >已保存记忆{{ savedUpdated ? ' · 有更新' : '' }}</BaseButton
      >
      <BaseButton
        :variant="tab === 'candidates' ? 'primary' : 'ghost'"
        :disabled="loading || busy"
        @click="selectTab('candidates')"
        >记忆建议</BaseButton
      >
    </nav>
    <div v-if="tab === 'saved'">
      <p class="work-muted">
        这里的内容仅用于当前账号与这个机器人的交流。适合保存作品偏好或明确事实，请勿填写密码、API
        Key 等凭据。
      </p>
      <p v-if="readOnly" class="work-muted">
        当前为历史机器人，可查看和删除记忆。选择有效机器人后可新增或修改。
      </p>
      <p v-if="error" class="work-error" role="alert">{{ error }}</p>
      <p v-if="success" class="work-success" role="status">{{ success }}</p>
      <div class="work-toolbar">
        <label class="work-check"
          ><input
            type="checkbox"
            :checked="settings?.memoryEnabled ?? false"
            :disabled="
              loading ||
              busy ||
              !settings ||
              unavailable ||
              conflict ||
              (readOnly && !settings.memoryEnabled)
            "
            @change="setEnabled"
          />聊天时使用已保存的记忆</label
        >
        <BaseButton size="small" variant="ghost" :disabled="loading || busy" @click="reload"
          >重新加载</BaseButton
        >
      </div>
      <p class="work-muted">
        开启后，部分已保存的记忆会随对话发送给你选择的模型服务，帮助伙伴了解你的喜好。关闭后会停止使用这些记忆。删除记忆会清除保存的内容，已有聊天记录会保留。
      </p>
      <AiMemoryVectorSettings
        :key="botId"
        :bot-id="botId"
        :memory-enabled="settings?.memoryEnabled ?? null"
        :read-only="readOnly"
      />
      <p v-if="loading" class="work-muted" role="status">正在读取私有记忆…</p>
      <form v-if="!readOnly" class="work-form ai-memory-editor" @submit.prevent="save">
        <h3>{{ editing ? '修改已保存的记忆' : '手动添加记忆' }}</h3>
        <fieldset :disabled="!canWrite || busy || loading">
          <label
            >记忆类型<select v-model="memoryType" required>
              <option value="preference">偏好</option>
              <option value="fact">事实</option>
            </select></label
          >
          <label
            >内容<textarea
              v-model="content"
              required
              rows="3"
              maxlength="2000"
              placeholder="例如 我更喜欢轻松日常、篇幅较短的作品"
            />
          </label>
          <p class="work-muted" :class="{ 'memory-over-limit': characterCount > 1000 }">
            {{ characterCount }} / 1000 个字符
          </p>
          <label class="work-check"
            ><input
              v-model="confirmed"
              type="checkbox"
              required
            />确认将以上内容保存为这个机器人的私有记忆</label
          >
          <div class="work-toolbar">
            <BaseButton
              type="submit"
              :disabled="!confirmed || !characterCount || characterCount > 1000"
              :loading="busy"
              >{{ editing ? '确认保存修改' : '确认添加' }}</BaseButton
            ><BaseButton v-if="editing" variant="ghost" @click="clearEditor">取消编辑</BaseButton>
          </div>
        </fieldset>
      </form>
      <section class="ai-memory-list" aria-label="已保存的私有记忆">
        <h3>已保存内容</h3>
        <p v-if="!loading && !unavailable && !memories.length" class="work-muted">
          这里还没有保存的记忆。你可以主动添加希望这个机器人记住的偏好或事实。
        </p>
        <article v-for="row in memories" :key="row.id" class="ai-memory-row">
          <header>
            <span class="work-badge">{{ row.memoryType === 'preference' ? '偏好' : '事实' }}</span
            ><small class="work-muted">更新于 {{ formatDate(row.updateTime) }}</small>
          </header>
          <p class="work-text">{{ row.content }}</p>
          <small v-if="row.sourceMessageId" class="work-muted"
            >来自聊天中保存的内容</small
          >
          <div class="work-toolbar">
            <BaseButton
              v-if="!readOnly"
              size="small"
              variant="secondary"
              :disabled="!canWrite || busy || loading"
              @click="startEdit(row)"
              >修改</BaseButton
            ><button
              type="button"
              class="work-danger"
              :disabled="busy || loading || unavailable || conflict"
              @click="remove(row)"
            >
              删除
            </button>
          </div>
        </article>
        <BaseButton
          v-if="nextCursor"
          size="small"
          variant="ghost"
          :disabled="loading || busy || unavailable || conflict"
          @click="load(true)"
          >读取更早的记忆</BaseButton
        >
      </section>
    </div>
    <AiMemoryCandidatesPanel
      v-else
      :key="botId"
      :bot-id="botId"
      :read-only="readOnly"
      @saved="savedUpdated = true"
      @show-saved="selectTab('saved')"
    />
  </ActionDialog>
</template>

<style scoped>
.ai-memory-editor {
  margin-top: 1.4rem;
  padding: 1.1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.ai-memory-editor fieldset {
  display: grid;
  gap: 0.75rem;
  padding: 0;
  margin: 0;
  border: 0;
  min-width: 0;
}
.ai-memory-editor fieldset > p {
  margin: 0;
}
.ai-memory-editor .memory-over-limit {
  color: var(--color-danger);
}
.ai-memory-list {
  margin-top: 1.5rem;
}
.ai-memory-row {
  padding: 1rem 0;
  border-bottom: 1px solid var(--color-border);
}
.ai-memory-row header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.6rem;
}
.ai-memory-row .work-toolbar {
  margin-bottom: 0;
}
@media (max-width: 600px) {
  .ai-memory-editor {
    padding: 0.8rem;
  }
}
</style>
