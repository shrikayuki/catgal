<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { bindExternal, getExternalLinks, previewExternal, searchExternal, unbindExternal } from '../../api/catalog'
import type { ExternalLinkState, ExternalPreview, ExternalSearchResult, ExternalSource } from '../../types/catalog'
import BaseButton from '../ui/BaseButton.vue'
import CoverImage from '../content/CoverImage.vue'
import { useNotificationStore } from '../../stores/notifications'

const props = defineProps<{ gameId: number; gameName: string }>()
const emit = defineEmits<{ changed: [] }>()
const notices = useNotificationStore()
const source = ref<ExternalSource>('bangumi')
const keyword = ref(props.gameName)
const externalId = ref('')
const state = ref<ExternalLinkState | null>(null)
const result = ref<ExternalSearchResult | null>(null)
const preview = ref<ExternalPreview | null>(null)
const busy = ref(false)
const loadingLinks = ref(false)
const error = ref('')
const current = computed(() => state.value?.links.find(link => link.provider === source.value))
let controller: AbortController | undefined

async function loadLinks() {
  loadingLinks.value = true
  error.value = ''
  state.value = null
  try { state.value = await getExternalLinks(props.gameId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '关联加载失败' }
  finally { loadingLinks.value = false }
}

async function search(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  busy.value = true
  error.value = ''
  preview.value = null
  try {
    const response = await searchExternal(source.value, keyword.value.trim(), pageNo, request.signal)
    if (!request.signal.aborted) result.value = response
  }
  catch (cause) { if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '搜索失败' }
  finally { if (!request.signal.aborted) busy.value = false }
}

async function inspect(id: string) {
  busy.value = true
  error.value = ''
  preview.value = null
  controller?.abort()
  const request = new AbortController()
  controller = request
  try {
    const response = await previewExternal(source.value, id.trim(), request.signal)
    if (!request.signal.aborted) preview.value = response
  }
  catch (cause) { if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '预览失败' }
  finally { if (!request.signal.aborted) busy.value = false }
}

async function bind() {
  if (!preview.value || !state.value) return
  if (!window.confirm(`将「${props.gameName}」关联到 ${source.value} 的「${preview.value.title}」？${current.value ? '这会替换当前来源的关联。' : ''}`)) return
  busy.value = true
  error.value = ''
  try {
    state.value = await bindExternal(props.gameId, source.value, preview.value.externalId, state.value.revision)
    notices.show('外部条目已关联', 'success')
    emit('changed')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '关联失败' }
  finally { busy.value = false }
}

async function unbind(provider: ExternalSource) {
  if (!state.value || !window.confirm(`确认解除 ${provider} 关联？`)) return
  busy.value = true
  error.value = ''
  try {
    state.value = await unbindExternal(props.gameId, provider, state.value.revision)
    notices.show('关联已解除', 'success')
    emit('changed')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '解除失败' }
  finally { busy.value = false }
}

watch(source, () => { controller?.abort(); result.value = null; preview.value = null; externalId.value = ''; error.value = ''; busy.value = false })
watch(() => props.gameId, () => void loadLinks(), { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="external-panel">
    <div class="catalog-toolbar"><h3>来源关联</h3><BaseButton size="small" variant="ghost" :disabled="busy || loadingLinks" @click="loadLinks">刷新关联与修订号</BaseButton></div>
    <p class="catalog-hint">搜索并核对作品后绑定。来源标题保留为快照，游戏资料可在基础资料中维护。</p>
    <p v-if="loadingLinks" role="status">正在读取关联…</p>
    <ul class="external-links">
      <li v-for="link in state?.links ?? []" :key="link.provider"><a :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.provider === 'bangumi' ? 'Bangumi' : 'VNDB' }} · {{ link.title }} ↗</a><button class="catalog-danger" :disabled="busy" @click="unbind(link.provider)">解除关联</button></li>
    </ul>
    <p v-if="state?.links.length === 0" class="catalog-hint">尚未绑定来源条目。</p>
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <fieldset :disabled="busy || loadingLinks" class="external-controls">
      <label>资料来源<select v-model="source"><option value="bangumi">Bangumi 游戏</option><option value="vndb">VNDB 视觉小说</option></select></label>
      <form class="catalog-toolbar" @submit.prevent="search()"><label class="sr-only" for="external-search">搜索外部条目</label><input id="external-search" v-model="keyword" required maxlength="100" placeholder="输入作品名称" /><BaseButton type="submit" variant="secondary" :disabled="busy">搜索条目</BaseButton></form>
      <form class="catalog-toolbar" @submit.prevent="inspect(externalId)"><label class="sr-only" for="external-id">条目 ID</label><input id="external-id" v-model="externalId" required maxlength="32" :placeholder="source === 'bangumi' ? '已知条目 ID，例如 100' : '已知条目 ID，例如 v17'" /><BaseButton type="submit" variant="ghost" :disabled="busy">按 ID 预览</BaseButton></form>
    </fieldset>
    <p v-if="busy" role="status">正在处理，请稍候…</p>
    <article v-if="preview" class="external-preview catalog-card">
      <div v-if="preview.coverUrl" class="external-cover"><CoverImage :src="preview.coverUrl" :alt="preview.title" /></div>
      <div><p class="catalog-eyebrow">{{ preview.provider }} / {{ preview.externalId }}</p><h3>{{ preview.title }}</h3><p>{{ preview.originalTitle }}</p><p class="catalog-hint">{{ preview.releaseDate || '发售日期未收录' }}</p><p class="external-description">{{ preview.description || '来源暂未提供简介。' }}</p><div class="catalog-toolbar"><a :href="preview.url" target="_blank" rel="noopener noreferrer">在来源站核对 ↗</a><BaseButton :disabled="busy || !state" @click="bind">{{ current ? '确认替换关联' : '确认绑定此条目' }}</BaseButton></div></div>
    </article>
    <div v-if="result" class="external-results">
      <button v-for="item in result.items" :key="item.externalId" type="button" class="external-result" :disabled="busy" @click="inspect(item.externalId)"><strong>{{ item.title }}</strong><small>{{ item.originalTitle }} · {{ item.externalId }} · {{ item.releaseDate || '日期未收录' }}</small><span>查看详情 →</span></button>
      <p v-if="result.items.length === 0">未找到条目，可更换关键词或按 ID 预览。</p>
      <div class="catalog-toolbar"><BaseButton size="small" variant="ghost" :disabled="busy || result.pageNo <= 1" @click="search(result.pageNo - 1)">上一页</BaseButton><small>第 {{ result.pageNo }} 页</small><BaseButton size="small" variant="ghost" :disabled="busy || !result.hasMore || result.pageNo >= 20" @click="search(result.pageNo + 1)">下一页</BaseButton></div>
    </div>
  </section>
</template>
