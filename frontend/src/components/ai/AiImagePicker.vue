<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { ImagePlus } from 'lucide-vue-next'
import { imageAccept, localImageError } from '../../utils/image-upload'
import BaseButton from '../ui/BaseButton.vue'
import AiImage from './AiImage.vue'

const props = defineProps<{
  modelValue: File | null
  src: string
  label: string
  removed: boolean
  disabled: boolean
  portrait?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [file: File | null]
  remove: []
  restore: []
}>()
const inputId = useId()
const input = ref<HTMLInputElement>()
const error = ref('')
const preview = ref('')
const displayedImage = computed(() => preview.value || (!props.removed ? props.src : ''))

function releasePreview() {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
}

watch(() => props.modelValue, file => {
  releasePreview()
  if (file) {
    error.value = ''
    preview.value = URL.createObjectURL(file)
  }
  else if (input.value) input.value.value = ''
}, { immediate: true })

function choose(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  if (!file || props.disabled) return
  const problem = localImageError(file)
  if (problem) {
    error.value = problem
    element.value = ''
    return
  }
  error.value = ''
  emit('update:modelValue', file)
}

function imageFailed() {
  if (props.modelValue) {
    emit('update:modelValue', null)
    error.value = '这张图片暂时无法预览，请重新选择'
  } else error.value = '当前图片暂时无法显示，可以选择一张新图片'
}

onBeforeUnmount(releasePreview)
</script>

<template>
  <section class="image-picker" :class="{ 'image-picker--portrait': portrait }" :aria-label="label">
    <div class="image-picker__preview">
      <AiImage v-if="displayedImage" :src="displayedImage" :alt="`${label}预览`" @error="imageFailed" />
      <ImagePlus v-else :size="28" aria-hidden="true" />
    </div>
    <div class="image-picker__controls">
      <label :for="inputId">{{ label }}</label>
      <input :id="inputId" ref="input" type="file" :accept="imageAccept" :disabled="disabled" @change="choose" />
      <p class="work-muted">JPG、PNG、GIF、WebP，最大 2 MB，宽高不超过 4096 像素。</p>
      <p v-if="modelValue" class="work-muted" role="status">{{ modelValue.name }}，保存后生效。</p>
      <p v-else-if="removed" class="work-muted" role="status">这张图片将在保存时移除。</p>
      <div class="image-picker__actions">
        <BaseButton v-if="modelValue" size="small" variant="ghost" :disabled="disabled"
          @click="emit('update:modelValue', null)">取消选择</BaseButton>
        <BaseButton v-if="removed" size="small" variant="ghost" :disabled="disabled"
          @click="emit('restore')">撤销移除</BaseButton>
        <BaseButton v-else-if="src" size="small" variant="ghost" :disabled="disabled"
          @click="emit('remove')">移除{{ label }}</BaseButton>
      </div>
      <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.image-picker {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  align-items: start;
  gap: 1rem;
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.image-picker__preview {
  display: grid;
  place-items: center;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: 50%;
  background: var(--color-brand-50);
  color: var(--color-brand-600);
}
.image-picker--portrait .image-picker__preview {
  aspect-ratio: 3 / 4;
  border-radius: var(--radius-sm);
}
.image-picker__preview :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.image-picker--portrait .image-picker__preview :deep(img) { object-fit: contain; }
.image-picker__controls { min-width: 0; }
.image-picker__controls label { margin-bottom: 0.6rem; font-weight: 700; }
.image-picker__controls input { width: 100%; min-width: 0; }
.image-picker__controls p { margin-block: 0.6rem; }
.image-picker__actions { display: flex; flex-wrap: wrap; }
@media (max-width: 480px) {
  .image-picker { grid-template-columns: minmax(0, 1fr); }
  .image-picker__preview { width: 5rem; }
}
</style>
