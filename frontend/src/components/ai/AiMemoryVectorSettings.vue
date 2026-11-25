<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getAiMemoryVectorSettings, saveAiMemoryVectorSettings } from '../../api/ai'
import { ApiError } from '../../api/types'
import { useAuthStore } from '../../stores/auth'
import type { AiMemoryVectorSettings } from '../../types/ai'
import BaseButton from '../ui/BaseButton.vue'

const props = withDefaults(
  defineProps<{ botId: string; memoryEnabled: boolean | null; readOnly?: boolean }>(),
  { readOnly: false },
)
const auth = useAuthStore()
const settings = ref<AiMemoryVectorSettings | null>(null)
const loading = ref(false),
  busy = ref(false),
  unavailable = ref(false),
  needsReload = ref(false)
const error = ref(''),
  success = ref('')
const controller = new AbortController()
let disposed = false

function clearPrivateState() {
  disposed = true
  controller.abort()
  settings.value = null
  error.value = ''
  success.value = ''
}
function handleError(cause: unknown, writing = false) {
  if (disposed || controller.signal.aborted) return
  if (cause instanceof ApiError && cause.status === 503) {
    unavailable.value = true
    error.value = '记忆匹配暂时无法使用，请稍后刷新设置。'
  } else if (cause instanceof ApiError && (cause.status === 409 || cause.status === 404)) {
    needsReload.value = true
    error.value = '伙伴或记忆设置已更新，请刷新后再试。'
  } else if (
    writing &&
    (!(cause instanceof ApiError) ||
      cause.status === 0 ||
      cause.status >= 500 ||
      cause.status === 200)
  ) {
    needsReload.value = true
    error.value = '本次授权操作结果需要核对，请先重新读取设置，再决定是否继续。'
  } else {
    if (cause instanceof RangeError) needsReload.value = true
    error.value = cause instanceof Error ? cause.message : '记忆设置加载失败，请刷新后重试'
  }
}
async function load() {
  if (disposed || loading.value || busy.value) return
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await getAiMemoryVectorSettings(props.botId, controller.signal)
    if (disposed) return
    settings.value = result
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
  const current = settings.value
  const enabled = input.checked
  input.checked = current?.enabled ?? false
  if (
    disposed ||
    !current ||
    loading.value ||
    busy.value ||
    unavailable.value ||
    needsReload.value ||
    (enabled && (props.readOnly || props.memoryEnabled !== true))
  )
    return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await saveAiMemoryVectorSettings(
      props.botId,
      current.revision,
      enabled,
      controller.signal,
    )
    if (disposed) return
    settings.value = result
    success.value = result.enabled
      ? '已开启记忆匹配，伙伴会在聊天中查找相关的记忆'
      : '已关闭记忆匹配，相关模型调用已停止，匹配信息将逐步清理，已保存的记忆会保留'
  } catch (cause) {
    handleError(cause, true)
  } finally {
    if (!disposed) busy.value = false
  }
}
watch([() => props.botId, () => auth.session?.userId], clearPrivateState, { flush: 'sync' })
onBeforeUnmount(clearPrivateState)
void load()
</script>

<template>
  <section class="ai-memory-vector" aria-label="按含义查找记忆">
    <h3>按含义查找记忆</h3>
    <p id="ai-memory-vector-consent" class="work-muted">
      此功能默认关闭。开启即授权将已确认保存的记忆，以及当前提问的前 2000
      个字符，发送至平台使用的记忆检索模型，可能产生额外费用。该服务与当前聊天模型分别配置和计费。
    </p>
    <p class="work-muted">
      伙伴会根据当前话题查找相关记忆。匹配暂时不可用时，会参考近期保存的记忆继续聊天。
    </p>
    <p class="work-muted">
      关闭后会停止相关模型调用，并逐步清理用于匹配的信息。你确认保存的记忆会保留，聊天时是否使用由记忆总开关决定。
    </p>
    <p v-if="memoryEnabled === false" class="work-muted">
      请先开启“聊天时使用已保存的记忆”，再开启此功能。你也可以随时关闭已有授权。
    </p>
    <p v-else-if="memoryEnabled === null" class="work-muted">正在检查记忆开关…</p>
    <p v-if="readOnly" class="work-muted">当前为历史伙伴，可以查看设置或关闭授权。</p>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="success" class="work-success" role="status">{{ success }}</p>
    <div class="work-toolbar">
      <label class="work-check"
        ><input
          type="checkbox"
          aria-describedby="ai-memory-vector-consent"
          :checked="settings?.enabled ?? false"
          :disabled="
            loading ||
            busy ||
            !settings ||
            unavailable ||
            needsReload ||
            ((readOnly || memoryEnabled !== true) && !settings.enabled)
          "
          @change="setEnabled"
        />按话题查找相关记忆</label
      >
      <BaseButton size="small" variant="ghost" :disabled="loading || busy" @click="load"
        >刷新记忆设置</BaseButton
      >
    </div>
    <p v-if="loading" class="work-muted" role="status">正在读取记忆设置…</p>
  </section>
</template>

<style scoped>
.ai-memory-vector {
  padding: 1rem;
  margin: 1.2rem 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.ai-memory-vector h3 {
  margin-top: 0;
}
.ai-memory-vector .work-toolbar {
  margin-bottom: 0;
}
@media (max-width: 600px) {
  .ai-memory-vector {
    padding: 0.8rem;
  }
}
</style>
