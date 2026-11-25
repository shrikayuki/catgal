<script setup lang="ts">
import { directoryRoute, readPublicId } from '../utils/navigation'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getDirectoryPage, getDirectoryEntry, type DirectoryKind } from '../api/discovery'
import { getGames } from '../api/games'
import type { CatalogEntry } from '../types/catalog'
import type { GameSummary } from '../types/game'
import type { PageData } from '../api/types'
import BaseButton from '../components/ui/BaseButton.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
import GameCard from '../components/content/GameCard.vue'
const route = useRoute(),
  keyword = ref(''),
  page = ref(1),
  error = ref(''),
  loading = ref(false)
const kind = computed<DirectoryKind>(() => (route.params.kind === 'tags' ? 'tags' : 'companies'))
const label = computed(() => (kind.value === 'companies' ? '会社' : '标签'))
const entry = ref<CatalogEntry | null>(null),
  data = ref<PageData<CatalogEntry> | null>(null),
  games = ref<PageData<GameSummary> | null>(null)
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  entry.value = null
  data.value = null
  games.value = null
  try {
    if (route.params.entryId) {
      const id = readPublicId(route.params.entryId)
      const [detail, results] = await Promise.all([
        getDirectoryEntry(kind.value, id, request.signal),
        getGames(
          {
            pageNo,
            pageSize: 12,
            ...(kind.value === 'companies' ? { companyId: id } : { tagId: id }),
          },
          request.signal,
        ),
      ])
      if (!request.signal.aborted) {
        entry.value = detail
        games.value = results
      }
    } else {
      const result = await getDirectoryPage(
        kind.value,
        keyword.value.trim(),
        pageNo,
        request.signal,
      )
      if (!request.signal.aborted) data.value = result
    }
    if (!request.signal.aborted) page.value = pageNo
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '资料加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
watch(
  () => route.fullPath,
  () => {
    keyword.value = ''
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <div class="container section workspace-page">
    <header>
      <h1>{{ entry?.name || `${label}目录` }}</h1>
      <nav class="work-tabs">
        <RouterLink to="/catalog/companies">会社</RouterLink>
        <RouterLink to="/catalog/tags">标签</RouterLink>
      </nav>
    </header>
    <form v-if="!route.params.entryId" class="work-toolbar" @submit.prevent="load()">
      <input
        v-model="keyword"
        :aria-label="`搜索${label}`"
        maxlength="100"
        placeholder="输入名称搜索"
      />
      <BaseButton type="submit" :disabled="loading">搜索</BaseButton>
    </form>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取资料…</p>
    <div v-if="data" class="work-grid">
      <article v-for="item in data.list" :key="item.id" class="work-card">
        <h2>{{ item.name }}</h2>
        <div class="work-toolbar">
          <BaseButton :to="directoryRoute(kind, item.id)" variant="secondary" size="small">
            浏览关联作品
          </BaseButton>
        </div>
      </article>
    </div>
    <template v-if="entry">
      <p class="work-muted">
        {{ games?.total ?? 0 }} 部相关作品
      </p>
      <div class="work-grid">
        <GameCard v-for="game in games?.list ?? []" :key="game.id" :game="game" />
      </div>
    </template>
    <p v-if="!loading && (data?.list.length === 0 || games?.list.length === 0)" class="work-empty">
      暂无匹配资料或作品。
    </p>
    <PaginationControl
      v-if="data || games"
      :current-page="page"
      :total-pages="(data || games)!.pages"
      @change="load"
    />
  </div>
</template>
