<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { clearAiSummary, getAiSummary, saveAiSummary } from '../../api/ai'
import { ApiError } from '../../api/types'
import type { AiSessionSummary } from '../../types/ai'
import { formatDate } from '../../utils/format'
import { useAuthStore } from '../../stores/auth'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'

const props = withDefaults(
  defineProps<{ sessionId: string; sessionTitle: string; readOnly?: boolean }>(),
  { readOnly: false },
)
const emit = defineEmits<{ close: [] }>()
const auth = useAuthStore()
const summary = ref<AiSessionSummary | null>(null)
const loading = ref(false),
  busy = ref(false),
  unavailable = ref(false),
  needsReload = ref(false)
const error = ref(''),
  success = ref('')
const statusLabel = computed(() => {
  if (!summary.value) return '等待加载'
  return { empty: '尚未生成摘要', ready: '已有摘要', failed: '最近一次归纳失败' }[
    summary.value.status
  ]
})
const controller = new AbortController()
let disposed = false

function clearPrivateState() {
  disposed = true
  controller.abort()
  summary.value = null
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
    error.value = '滚动摘要服务尚未启用或暂不可用，请稍后刷新或联系管理员。'
  } else if (cause instanceof ApiError && cause.status === 409) {
    needsReload.value = true
    error.value = '会话摘要已在其他窗口更新，请刷新状态并核对后再次操作。'
  } else if (
    writing &&
    (!(cause instanceof ApiError) ||
      cause.status === 0 ||
      cause.status >= 500 ||
      cause.status === 200)
  ) {
    needsReload.value = true
    error.value = '本次提交结果需要核对，请先刷新状态，再决定是否继续操作。'
  } else error.value = cause instanceof Error ? cause.message : '摘要操作失败，请刷新状态后重试'
}
async function load() {
  if (disposed || loading.value || busy.value) return
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await getAiSummary(props.sessionId, controller.signal)
    if (disposed) return
    summary.value = result
    unavailable.value = false
    needsReload.value = false
  } catch (cause) {
    handleError(cause)
  } finally {
    if (!disposed) loading.value = false
  }
}
async function setEnabled(event: Event) {
  const input = event.target as HTMLInputElement
  const current = summary.value
  const enabled = input.checked
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
    const result = await saveAiSummary(
      props.sessionId,
      current.revision,
      enabled,
      controller.signal,
    )
    if (disposed) return
    summary.value = result
    success.value = result.enabled
      ? '已开启本会话的滚动摘要，后续聊天将按需更新'
      : '已关闭后续摘要归纳，保存的摘要仍可查看或清除'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
async function clear() {
  const current = summary.value
  if (!current || disposed || busy.value || loading.value || unavailable.value || needsReload.value)
    return
  if (!window.confirm('清除这个会话的摘要并关闭摘要功能？聊天原文将保留。')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await clearAiSummary(props.sessionId, current.revision, controller.signal)
    if (disposed) return
    summary.value = result
    success.value = '摘要已清除，摘要功能已关闭，聊天原文保留'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
watch(
  [() => props.sessionId, () => auth.session?.userId],
  () => {
    clearPrivateState()
    emit('close')
  },
  { flush: 'sync' },
)
onBeforeUnmount(clearPrivateState)
void load()
</script>

<template>
  <ActionDialog title="会话滚动摘要" @close="emit('close')">
    <h3>{{ sessionTitle }}</h3>
    <p class="work-muted">
      摘要仅作用于当前会话，默认关闭。开启后，归纳使用当前机器人绑定的模型与 API
      Key，会产生额外模型调用费用。
    </p>
    <p class="work-muted">
      聊天将组合最近 10
      轮原文与更早的滚动摘要。长历史会随后续聊天分批逐步归纳，归纳失败时使用近期原文继续回复。
    </p>
    <p v-if="readOnly" class="work-muted">当前为历史机器人会话，可查看、关闭或清除摘要。</p>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="success" class="work-success" role="status">{{ success }}</p>
    <div class="work-toolbar">
      <label class="work-check"
        ><input
          type="checkbox"
          :checked="summary?.enabled ?? false"
          :disabled="
            loading ||
            busy ||
            !summary ||
            unavailable ||
            needsReload ||
            (readOnly && !summary.enabled)
          "
          @change="setEnabled"
        />开启本会话滚动摘要</label
      >
      <BaseButton size="small" variant="secondary" :disabled="loading || busy" @click="load"
        >刷新状态</BaseButton
      >
    </div>
    <p class="work-muted">
      刷新状态用于查看已保存进度。关闭会停止后续归纳，已有摘要仍可查看。清除会关闭功能并保留聊天原文。
    </p>
    <p v-if="loading" class="work-muted" role="status">正在读取摘要状态…</p>
    <section v-if="summary" class="ai-summary-state" aria-label="已保存的会话摘要">
      <div class="ai-summary-meta">
        <span class="work-badge">{{ statusLabel }}</span
        ><span class="work-muted">已覆盖 {{ summary.coveredTurns }} 轮</span
        ><time v-if="summary.updateTime" class="work-muted" :datetime="summary.updateTime"
          >更新于 {{ formatDate(summary.updateTime) }}</time
        >
      </div>
      <p v-if="summary.summary" class="work-text ai-summary-text">{{ summary.summary }}</p>
      <p v-else class="work-muted">当前暂无已保存摘要。启用后会随聊天历史积累逐步归纳。</p>
      <p v-if="summary.lastErrorCode" class="work-muted" role="status">
        上次整理暂未完成，后续聊天时会再次尝试。
      </p>
      <button
        class="work-danger"
        type="button"
        :disabled="loading || busy || unavailable || needsReload"
        @click="clear"
      >
        清除摘要并关闭
      </button>
    </section>
  </ActionDialog>
</template>

<style scoped>
.ai-summary-state {
  border-top: 1px solid var(--color-border);
  padding-top: 1.2rem;
  margin-top: 1.2rem;
}
.ai-summary-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
}
.ai-summary-text {
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-brand-50);
}
</style>
