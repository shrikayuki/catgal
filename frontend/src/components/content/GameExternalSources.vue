<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getPublicExternalLinks } from '../../api/catalog'
import type { ExternalLink } from '../../types/catalog'

const props = defineProps<{ gameId: number }>()
const links = ref<ExternalLink[]>([])
const error = ref('')
const loading = ref(false)
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  links.value = []
  error.value = ''
  loading.value = true
  try { const result = await getPublicExternalLinks(props.gameId, request.signal); if (!request.signal.aborted) links.value = result }
  catch (cause) { if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '来源链接加载失败' }
  finally { if (!request.signal.aborted) loading.value = false }
}
watch(() => props.gameId, () => void load(), { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div v-if="links.length || error || loading" class="game-sources" aria-label="外部资料来源">
    <span>作品资料</span><small v-if="loading">正在读取来源…</small>
    <a v-for="link in links" :key="link.provider" :href="link.url" :title="link.title" target="_blank" rel="noopener noreferrer">{{ link.provider === 'bangumi' ? 'Bangumi' : 'VNDB' }} ↗</a>
    <button v-if="error" type="button" :title="error" @click="load">来源链接暂不可用，点击重试</button>
  </div>
</template>

<style scoped>
.game-sources { display: flex; align-items: center; flex-wrap: wrap; gap: .6rem; margin-block: 1rem; font-size: var(--text-sm); color: var(--color-text-secondary); }
.game-sources a, .game-sources button { border: 1px solid var(--color-border-strong); border-radius: var(--radius-pill); background: var(--color-surface); padding: .4rem .8rem; color: var(--color-brand-700); text-decoration: none; cursor: pointer; }
.game-sources a:hover { background: var(--color-brand-50); }
</style>
