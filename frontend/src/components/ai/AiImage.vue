<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getAiImage } from '../../api/ai'
import { useAuthStore } from '../../stores/auth'
import { privateAiImagePath } from '../../utils/image-upload'

const props = withDefaults(defineProps<{ src?: string | null; alt: string }>(), { src: '' })
const emit = defineEmits<{ error: [cause: unknown]; load: [event: Event] }>()
const auth = useAuthStore()
const source = ref('')
let request: AbortController | undefined
let objectUrl = ''

function release() {
  request?.abort()
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = ''
  source.value = ''
}

watch([() => props.src, () => auth.session?.token], async ([src]) => {
  release()
  if (!src) return
  if (!privateAiImagePath(src)) {
    source.value = src
    return
  }
  const current = new AbortController()
  request = current
  try {
    const blob = await getAiImage(src, current.signal)
    if (current.signal.aborted) return
    objectUrl = URL.createObjectURL(blob)
    source.value = objectUrl
  } catch (cause) {
    if (!current.signal.aborted) emit('error', cause)
  }
}, { immediate: true })

onBeforeUnmount(release)
</script>

<template>
  <img v-if="source" :src="source" :alt="alt" referrerpolicy="no-referrer"
    @error="emit('error', $event)" @load="emit('load', $event)" />
</template>
