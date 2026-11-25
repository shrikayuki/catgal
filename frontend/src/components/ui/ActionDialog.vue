<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { X } from 'lucide-vue-next'
const props = defineProps<{ title: string; busy?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const titleId = useId()
let closeEmitted = false

function notifyClosed() {
  if (closeEmitted) return
  closeEmitted = true
  emit('close')
}

function dismiss() {
  if (props.busy || !dialog.value?.open) return
  // 主动关闭时同步通知父页面，原生 close 事件稍后到达时去重。
  dialog.value.close()
  notifyClosed()
}

function backdrop(event: MouseEvent) {
  if (props.busy || event.target !== dialog.value) return
  const rect = dialog.value?.getBoundingClientRect()
  if (
    rect &&
    (event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom)
  )
    dismiss()
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => { closeEmitted = true; dialog.value?.close() })
</script>
<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="work-dialog workspace-page"
      :aria-labelledby="titleId"
      @close="notifyClosed"
      @cancel.prevent="dismiss"
      @click="backdrop"
    >
      <header class="work-toolbar work-dialog__heading">
        <h2 :id="titleId">{{ title }}</h2>
        <button
          type="button"
          class="work-icon-button"
          aria-label="关闭窗口"
          :disabled="props.busy"
          autofocus
          @click="dismiss"
        >
          <X :size="20" />
        </button>
      </header>
      <slot />
    </dialog>
  </Teleport>
</template>

<style scoped>
.work-dialog > .work-dialog__heading { flex-wrap: nowrap; gap: .7rem; margin: 0 0 1rem; padding-bottom: .85rem; }
.work-dialog__heading h2 { min-width: 0; color: var(--color-brand-900); }
.work-dialog__heading button { flex: none; }
</style>
