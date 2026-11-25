<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { clearGameCover, getGameCoverImage, uploadGameCover } from '../../api/catalog'
import type { AdminGame, GameCoverUpdate } from '../../types/catalog'
import BaseButton from '../ui/BaseButton.vue'

const props = defineProps<{ game: AdminGame | null; disabled: boolean }>()
const emit = defineEmits<{ updated: [value: GameCoverUpdate]; dirty: [value: boolean]; busy: [value: boolean] }>()
const selectedFile = ref<File | null>(null)
const preview = ref('')
const previewError = ref('')
const error = ref('')
const loading = ref(false)
const working = ref(false)
const input = ref<HTMLInputElement | null>(null)
let previewRequest: AbortController | undefined
let operationRequest: AbortController | undefined

watch(selectedFile, file => emit('dirty', file !== null))

function releasePreview() {
  previewRequest?.abort()
  if (preview.value.startsWith('blob:')) URL.revokeObjectURL(preview.value)
  preview.value = ''
  previewError.value = ''
}

async function loadPreview() {
  releasePreview()
  if (selectedFile.value) {
    preview.value = URL.createObjectURL(selectedFile.value)
    loading.value = false
    return
  }
  const id = props.game?.coverFileId
  if (!id) {
    preview.value = props.game?.coverUrl ?? ''
    loading.value = false
    return
  }
  const request = new AbortController()
  previewRequest = request
  loading.value = true
  try {
    const blob = await getGameCoverImage(id, request.signal)
    if (!request.signal.aborted) preview.value = URL.createObjectURL(blob)
  } catch (cause) {
    if (!request.signal.aborted) previewError.value = cause instanceof Error ? cause.message : '封面读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

watch(() => [props.game?.id, props.game?.coverFileId, props.game?.coverUrl], () => { void loadPreview() }, { immediate: true })

function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  error.value = ''
  if (!file) return
  if (file.size === 0 || file.size > 2 * 1024 * 1024) {
    error.value = '封面须为非空图片，最大2MiB'
    if (input.value) input.value.value = ''
    return
  }
  selectedFile.value = file
  void loadPreview()
}

function cancelSelection() {
  selectedFile.value = null
  if (input.value) input.value.value = ''
  error.value = ''
  void loadPreview()
}

async function mutate(operation: (signal: AbortSignal) => Promise<GameCoverUpdate>) {
  const request = new AbortController()
  operationRequest = request
  working.value = true
  emit('busy', true)
  error.value = ''
  try {
    const result = await operation(request.signal)
    if (request.signal.aborted) return
    selectedFile.value = null
    if (input.value) input.value.value = ''
    emit('updated', result)
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '封面更新失败'
  } finally {
    working.value = false
    emit('busy', false)
  }
}

async function upload() {
  const game = props.game
  const file = selectedFile.value
  if (!game || !file || props.disabled || working.value) return
  await mutate(signal => uploadGameCover(game.id, game.revision, file, signal))
}

async function clear() {
  const game = props.game
  if (!game || props.disabled || working.value || selectedFile.value) return
  if (!window.confirm(`确认移除「${game.name}」的封面？`)) return
  await mutate(signal => clearGameCover(game.id, game.revision, signal))
}

onBeforeUnmount(() => {
  operationRequest?.abort()
  releasePreview()
  emit('dirty', false)
  if (working.value) emit('busy', false)
})
</script>

<template>
  <section class="cover-editor catalog-card" aria-label="游戏封面">
    <div class="cover-preview">
      <img v-if="preview && !previewError" :key="preview" :src="preview" :alt="`${game?.name || '游戏'}封面`" @error="previewError = '封面暂时无法显示'" />
      <span v-else>{{ loading ? '读取封面中…' : previewError || '暂无封面' }}</span>
    </div>
    <div class="cover-actions">
      <h3>游戏封面</h3>
      <p v-if="!game" class="catalog-hint">保存游戏草稿后即可上传封面。</p>
      <template v-else>
        <label>选择封面图片<input ref="input" type="file" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="disabled || working" @change="chooseFile" /></label>
        <p class="catalog-hint">JPG、PNG、GIF、WebP，最大2MiB，边长最大4096。</p>
        <p v-if="disabled && !working" class="catalog-hint">保存基础资料后可管理封面。</p>
        <div class="catalog-toolbar">
          <BaseButton v-if="selectedFile" :loading="working" :disabled="disabled" @click="upload">上传封面</BaseButton>
          <BaseButton v-if="selectedFile" variant="ghost" :disabled="working" @click="cancelSelection">取消选择</BaseButton>
          <BaseButton v-else-if="game.coverUrl || game.coverFileId" variant="ghost" :disabled="disabled || working" @click="clear">移除封面</BaseButton>
        </div>
      </template>
      <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.cover-editor { display: grid; grid-template-columns: 8rem minmax(0, 1fr); gap: 1.25rem; margin-bottom: 1rem; }
.cover-preview { display: grid; place-items: center; aspect-ratio: 3 / 4; overflow: hidden; border-radius: .75rem; background: var(--color-surface-soft, #f3f4f8); color: var(--color-text-muted); text-align: center; font-size: .8rem; }
.cover-preview img { width: 100%; height: 100%; object-fit: cover; }
.cover-actions { min-width: 0; }
.cover-actions h3 { margin: 0 0 .75rem; }
.cover-actions input { max-width: 100%; }
@media (max-width: 540px) { .cover-editor { grid-template-columns: minmax(0, 1fr); } .cover-preview { width: 8rem; } }
</style>
