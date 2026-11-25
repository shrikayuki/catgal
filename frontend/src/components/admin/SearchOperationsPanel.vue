<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import {
  getOutbox,
  retryOutbox,
  rebuildIndex,
  type OutboxStatus,
  type RebuildResult,
  type SearchKind,
} from '../../api/operations'
import BaseButton from '../ui/BaseButton.vue'
const busy = defineModel<boolean>('busy', { required: true })
const kinds: SearchKind[] = ['games', 'resources']
const labels = { games: '游戏', resources: '资源' }
const data = ref<Record<SearchKind, OutboxStatus | null>>({ games: null, resources: null })
const results = ref<Partial<Record<SearchKind, RebuildResult>>>({})
const error = ref(''),
  message = ref(''),
  loading = ref(false)
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const [games, resources] = await Promise.all([
      getOutbox('games', request.signal),
      getOutbox('resources', request.signal),
    ])
    if (!request.signal.aborted) data.value = { games, resources }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '搜索积压读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function operate(kind: SearchKind, action: 'retry' | 'rebuild') {
  if (busy.value || loading.value) return
  const description =
    action === 'rebuild'
      ? `重建${labels[kind]}完整索引并切换搜索别名，耗时取决于条目数量`
      : `重新调度${labels[kind]}同步失败事件`
  if (!window.confirm(`确认${description}？`)) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    if (action === 'rebuild') results.value[kind] = await rebuildIndex(kind)
    else await retryOutbox(kind)
    message.value =
      action === 'rebuild'
        ? `${labels[kind]}索引重建完成。`
        : `${labels[kind]}失败事件已提交重试，后续由同步任务处理。`
    await load()
  } catch (cause) {
    error.value = `${cause instanceof Error ? cause.message : '操作失败'}。请求结果需核对，可刷新积压状态与服务日志确认。`
  } finally {
    busy.value = false
  }
}
void load()
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="work-stack">
    <div class="work-toolbar">
      <h2>搜索索引与同步事件</h2>
      <BaseButton variant="secondary" :disabled="busy || loading" @click="load">
        刷新搜索积压
      </BaseButton>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="message" class="work-success" role="status">{{ message }}</p>
    <p v-if="loading" role="status">正在读取索引事件…</p>
    <div class="work-grid">
      <article v-for="kind in kinds" :key="kind" class="work-card">
        <h3>{{ labels[kind] }}搜索</h3>
        <dl>
          <dt>待同步事件</dt>
          <dd>{{ data[kind]?.pending ?? '—' }}</dd>
          <dt>已失败事件</dt>
          <dd>{{ data[kind]?.failed ?? '—' }}</dd>
          <dt>最早积压</dt>
          <dd>{{ data[kind]?.oldest_event || '无' }}</dd>
        </dl>
        <div class="work-toolbar">
          <BaseButton
            size="small"
            variant="secondary"
            :disabled="busy || loading || !data[kind]"
            @click="operate(kind, 'retry')"
          >
            重试{{ labels[kind] }}失败事件
          </BaseButton>
          <button
            class="work-danger"
            :disabled="busy || loading || !data[kind]"
            @click="operate(kind, 'rebuild')"
          >
            重建{{ labels[kind] }}索引
          </button>
        </div>
        <div v-if="results[kind]" class="work-success">
          <p>索引 {{ results[kind]!.index }}</p>
          <p>扫描 {{ results[kind]!.scannedGames ?? results[kind]!.scannedResources ?? '—' }} 条</p>
        </div>
      </article>
    </div>
  </section>
</template>
