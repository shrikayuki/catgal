<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getResourceHistory, type ResourceHistoryItem } from '../../api/resources'
import { getAdminResourceHistory } from '../../api/admin'
import type { PageData } from '../../api/types'
import { resourceActionLabel, resourceChangedLabels, resourceHistoryTime, resourceStatusLabel } from '../../utils/resource-review'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'

const props = defineProps<{ resourceId: number; resourceName?: string; admin?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const data = ref<PageData<ResourceHistoryItem> | null>(null)
const page = ref(1)
const loading = ref(false)
const error = ref('')
let controller: AbortController | undefined

async function load(pageNo = 1): Promise<void> {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await (props.admin ? getAdminResourceHistory : getResourceHistory)(props.resourceId, pageNo, request.signal)
    if (request.signal.aborted) return
    data.value = result
    page.value = pageNo
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '记录加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
watch(() => [props.resourceId, props.admin], () => { data.value = null; void load() }, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <ActionDialog title="编辑与审核记录" @close="emit('close')">
    <div class="work-stack" :aria-busy="loading">
      <p v-if="resourceName" class="history-name">{{ resourceName }}</p>
      <p v-if="loading" role="status">正在读取记录…</p>
      <div v-if="error" class="work-error" role="alert">
        <p>{{ error }}</p><BaseButton variant="secondary" size="small" @click="load(page)">重新加载</BaseButton>
      </div>
      <ol v-if="data?.list.length" class="resource-history">
        <li v-for="item in data.list" :key="item.id">
          <div class="work-toolbar"><strong>{{ resourceActionLabel(item.action) }}</strong><time>{{ resourceHistoryTime(item.createTime) }}</time></div>
          <p class="work-muted">{{ item.actorName || '社区成员' }}<template v-if="item.newStatus != null"> · <template v-if="item.oldStatus != null">{{ resourceStatusLabel(item.oldStatus) }} → </template>{{ resourceStatusLabel(item.newStatus) }}</template></p>
          <p v-if="item.reason" class="work-text history-reason">{{ item.reason }}</p>
          <p v-if="resourceChangedLabels(item.changedFields).length" class="work-muted">调整了{{ resourceChangedLabels(item.changedFields).join('、') }}</p>
        </li>
      </ol>
      <p v-else-if="!loading && !error" class="work-empty">暂无编辑与审核记录。</p>
      <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
    </div>
  </ActionDialog>
</template>

<style scoped>
.history-name { margin: 0; overflow-wrap: anywhere; font-weight: 700; }
.resource-history { display: grid; gap: 1.25rem; margin: 0; padding: 0; list-style: none; }
.resource-history li { border-left: 2px solid var(--color-brand-300); padding-left: 1rem; }
.resource-history p { margin: 0.5rem 0 0; font-size: var(--text-sm); }
.resource-history time { color: var(--color-text-muted); font-size: var(--text-xs); }
.history-reason { border-radius: var(--radius-sm); background: var(--color-surface-soft); padding: 0.75rem; }
</style>
