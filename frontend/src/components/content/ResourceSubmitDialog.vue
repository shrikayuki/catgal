<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getEditableResource, submitResource, type EditableResource } from '../../api/resources'
import { hasResourceRevision, isResourceConflict, resourceStatusLabel } from '../../utils/resource-review'
import { useNotificationStore } from '../../stores/notifications'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'

const props = defineProps<{ resourceId: number }>()
const emit = defineEmits<{ close: []; submitted: [] }>()
const notices = useNotificationStore()
const resource = ref<EditableResource | null>(null)
const loading = ref(false), busy = ref(false), error = ref(''), conflict = ref(false)
let controller: AbortController | undefined
async function load(): Promise<void> {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  conflict.value = false
  resource.value = null
  try {
    const result = await getEditableResource(props.resourceId, request.signal)
    if (request.signal.aborted) return
    if (!hasResourceRevision(result.revision)) throw new Error('资源信息需要刷新，请稍后重新打开')
    resource.value = result
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '资源读取失败'
  } finally { if (!request.signal.aborted) loading.value = false }
}
async function submit(): Promise<void> {
  const current = resource.value
  if (busy.value || loading.value || conflict.value || !current || current.status !== 0) return
  busy.value = true
  error.value = ''
  try {
    await submitResource(current.id, current.revision)
    notices.show('资源已重新提交审核', 'success')
    emit('submitted')
  } catch (cause) {
    conflict.value = isResourceConflict(cause)
    error.value = conflict.value ? '资源已被更新，请刷新后核对，再重新提交。' : cause instanceof Error ? cause.message : '提交失败'
  } finally { busy.value = false }
}
watch(() => props.resourceId, () => void load(), { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <ActionDialog title="重新提交审核" :busy="busy" @close="emit('close')">
    <div class="work-stack">
      <p v-if="loading" role="status">正在读取最新资源信息…</p>
      <template v-if="resource">
        <h3>{{ resource.name }}</h3>
        <p class="work-muted">当前状态 {{ resourceStatusLabel(resource.status) }}</p>
        <p v-if="resource.reviewReason" class="work-text">审核说明：{{ resource.reviewReason }}</p>
        <p>{{ resource.status === 0 ? '请确认下载链接和内容已经符合审核要求，提交后将进入待审核列表。' : resource.status === 2 ? '资源已在审核中，无需重复提交。' : '资源当前有效，无需重新提交审核。' }}</p>
      </template>
      <p v-if="error" class="work-error" role="alert">{{ error }}</p>
      <div class="work-toolbar">
        <BaseButton v-if="resource?.status === 0" :loading="busy" :disabled="loading || conflict" @click="submit">确认重新送审</BaseButton>
        <BaseButton v-if="conflict || (!resource && !loading)" variant="secondary" :disabled="busy" @click="load">刷新资源信息</BaseButton>
        <BaseButton variant="ghost" :disabled="busy" @click="emit('close')">关闭</BaseButton>
      </div>
    </div>
  </ActionDialog>
</template>
