<script setup lang="ts">
import { readPublicId } from '../utils/navigation'
import { ChevronDown, RefreshCw, Search, SlidersHorizontal, X } from 'lucide-vue-next'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getGames } from '../api/games'
import type { PageData } from '../api/types'
import type { GamePageQuery, GameSummary } from '../types/game'
import BaseButton from '../components/ui/BaseButton.vue'
import GameCard from '../components/content/GameCard.vue'
import GameCardSkeleton from '../components/content/GameCardSkeleton.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
import SectionHeading from '../components/ui/SectionHeading.vue'
import StatePanel from '../components/feedback/StatePanel.vue'

const route = useRoute()
const router = useRouter()
const page = ref<PageData<GameSummary>>({ total: 0, pages: 0, list: [] })
const loading = ref(true)
const loadError = ref('')
const refinementsOpen = ref(false)
let requestController: AbortController | null = null

const filters = reactive({
  keyword: '',
  releaseYear: '',
  resourceType: '',
  language: '',
  platform: '',
  sortBy: '',
  isAsc: false,
})

function queryString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function queryNumber(value: unknown, fallback: number): number {
  const parsed = Number(queryString(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

const currentPage = computed(() => queryNumber(route.query.pageNo, 1))
const refinementCount = computed(() =>
  ['releaseYear', 'resourceType', 'language', 'platform', 'sortBy', 'isAsc']
    .filter((name) => name === 'isAsc' ? route.query.isAsc === 'true' : queryString(route.query[name])).length,
)

function syncFiltersFromRoute(): void {
  filters.keyword = queryString(route.query.keyword) || queryString(route.query.name)
  filters.releaseYear = queryString(route.query.releaseYear)
  filters.resourceType = queryString(route.query.resourceType)
  filters.language = queryString(route.query.language)
  filters.platform = queryString(route.query.platform)
  filters.sortBy = queryString(route.query.sortBy)
  filters.isAsc = queryString(route.query.isAsc) === 'true'
}

function buildRequestQuery(): GamePageQuery {
  return {
    pageNo: currentPage.value,
    pageSize: 12,
    companyId: readPublicId(route.query.company) || undefined,
    tagId: readPublicId(route.query.tag) || undefined,
    name: queryString(route.query.name) || undefined,
    keyword: queryString(route.query.keyword) || undefined,
    releaseYear: queryNumber(route.query.releaseYear, 0) || undefined,
    resourceType: queryString(route.query.resourceType) || undefined,
    language: queryString(route.query.language) || undefined,
    platform: queryString(route.query.platform) || undefined,
    sortBy: queryString(route.query.sortBy) || undefined,
    isAsc: queryString(route.query.isAsc) === 'true',
  }
}

async function loadGames(): Promise<void> {
  requestController?.abort()
  requestController = new AbortController()
  const request = requestController
  loading.value = true
  loadError.value = ''
  try {
    const result = await getGames(buildRequestQuery(), request.signal)
    if (!request.signal.aborted) page.value = result
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    loadError.value = error instanceof Error ? error.message : '游戏列表加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

function applyFilters(): void {
  const query: Record<string, string> = {}
  if (readPublicId(route.query.company)) query.company = String(route.query.company)
  if (readPublicId(route.query.tag)) query.tag = String(route.query.tag)
  if (filters.keyword.trim()) query.keyword = filters.keyword.trim()
  if (filters.releaseYear.trim()) query.releaseYear = filters.releaseYear.trim()
  if (filters.resourceType) query.resourceType = filters.resourceType
  if (filters.language) query.language = filters.language
  if (filters.platform) query.platform = filters.platform
  if (filters.sortBy) query.sortBy = filters.sortBy
  if (filters.isAsc) query.isAsc = 'true'
  void router.push({ name: 'games', query })
}

function resetFilters(): void {
  if (Object.keys(route.query).length) void router.push({ name: 'games' })
  else syncFiltersFromRoute()
}

function changePage(nextPage: number): void {
  void router.push({ query: { ...route.query, pageNo: nextPage > 1 ? String(nextPage) : undefined } })
}

watch(
  () => route.fullPath,
  () => {
    syncFiltersFromRoute()
    void loadGames()
  },
  { immediate: true },
)

onBeforeUnmount(() => requestController?.abort())
</script>

<template>
  <div class="library-page section">
    <div class="container">
      <SectionHeading
        title="游戏库"
        description="从一部喜欢的作品开始，找到下一个想读的故事。"
      />

      <form class="filter-panel" @submit.prevent="applyFilters">
        <div class="filter-panel__bar">
          <div class="filter-panel__search">
            <Search :size="20" aria-hidden="true" />
            <label class="sr-only" for="library-name">搜索游戏、介绍、标签或会社</label>
            <input id="library-name" v-model="filters.keyword" type="search" maxlength="200" placeholder="搜索游戏、介绍、标签或会社" />
          </div>
          <div class="filter-panel__buttons">
            <BaseButton type="submit">搜索</BaseButton>
            <BaseButton variant="secondary" class="filter-panel__toggle" :aria-expanded="refinementsOpen" aria-controls="library-refinements" @click="refinementsOpen = !refinementsOpen">
              <SlidersHorizontal :size="17" aria-hidden="true" />
              筛选 <span v-if="refinementCount" class="filter-panel__count">{{ refinementCount }}</span>
              <ChevronDown class="filter-panel__chevron" :size="15" aria-hidden="true" />
            </BaseButton>
          </div>
        </div>
        <p v-if="route.query.company || route.query.tag" class="filter-panel__selection">已按{{ route.query.company ? '会社' : '标签' }}筛选 <button type="button" @click="resetFilters">清除筛选</button></p>
        <fieldset v-show="refinementsOpen" id="library-refinements" class="filter-panel__fields">
          <legend class="sr-only">详细筛选条件</legend>
        <label>
          <span>发售年份</span>
          <input v-model="filters.releaseYear" inputmode="numeric" maxlength="4" placeholder="例如 2018" />
        </label>
        <label>
          <span>资源类型</span>
          <select v-model="filters.resourceType">
            <option value="">全部</option>
            <option value="pc">PC 本体</option>
            <option value="生肉">生肉</option>
            <option value="汉化">汉化</option>
            <option value="手机">手机</option>
            <option value="补丁">补丁</option>
            <option value="模拟器">模拟器</option>
            <option value="其他">其他</option>
          </select>
        </label>
        <label>
          <span>语言</span>
          <select v-model="filters.language">
            <option value="">全部</option>
            <option value="中文">中文</option>
            <option value="日语">日语</option>
            <option value="英语">英语</option>
            <option value="其他">其他</option>
          </select>
        </label>
        <label>
          <span>平台</span>
          <select v-model="filters.platform">
            <option value="">全部</option>
            <option value="win">Windows</option>
            <option value="mac">macOS</option>
            <option value="linux">Linux</option>
            <option value="android">Android</option>
            <option value="ios">iOS</option>
            <option value="其他">其他</option>
          </select>
        </label>
        <label>
          <span>排序</span>
          <select v-model="filters.sortBy">
            <option value="">默认排序</option>
            <option value="relevance">搜索相关性</option>
            <option value="create_time">最近收录</option>
            <option value="rating">评分</option>
            <option value="view_count">浏览数</option>
            <option value="favorite_count">收藏数</option>
            <option value="release_date">发售日期</option>
          </select>
        </label>
        <label class="direction-toggle">
          <input v-model="filters.isAsc" type="checkbox" :disabled="filters.sortBy === 'relevance' || (!filters.sortBy && !!filters.keyword.trim())" />
          <span>升序排列</span>
        </label>

        <div class="filter-panel__actions">
          <BaseButton type="submit" size="small">
            <SlidersHorizontal :size="17" aria-hidden="true" />
            应用筛选
          </BaseButton>
          <BaseButton type="button" variant="ghost" size="small" @click="resetFilters">
            <X :size="17" aria-hidden="true" />
            清除
          </BaseButton>
        </div>
        </fieldset>
      </form>

      <div class="result-summary" aria-live="polite">
        <span v-if="!loading && !loadError">找到 {{ page.total }} 部作品</span>
        <span v-else-if="loading">正在整理游戏档案…</span>
      </div>

      <div v-if="loading" class="game-grid" aria-label="正在加载游戏">
        <GameCardSkeleton v-for="item in 8" :key="item" />
      </div>
      <StatePanel v-else-if="loadError" tone="error" title="游戏库加载失败" :description="loadError">
        <BaseButton variant="secondary" @click="loadGames">
          <RefreshCw :size="17" aria-hidden="true" />
          重新加载
        </BaseButton>
      </StatePanel>
      <StatePanel
        v-else-if="page.list.length === 0"
        title="没有找到符合条件的作品"
        description="可以减少筛选条件，或者换一个游戏名称再试。"
      >
        <BaseButton variant="secondary" @click="resetFilters">清除全部筛选</BaseButton>
      </StatePanel>
      <template v-else>
        <div class="game-grid">
          <GameCard v-for="game in page.list" :key="game.id" :game="game" />
        </div>
        <PaginationControl :current-page="currentPage" :total-pages="page.pages" @change="changePage" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.library-page { min-height: 68vh; background: radial-gradient(ellipse at 100% 0%, var(--color-brand-100), transparent 40rem), var(--color-bg); }
.filter-panel { margin-top: var(--space-6); border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: var(--color-surface); box-shadow: var(--shadow-md); padding: clamp(.8rem, 2vw, 1.3rem); }
.filter-panel__bar { display: flex; align-items: center; gap: .7rem; }
.filter-panel__buttons { display: flex; flex-shrink: 0; gap: .55rem; }
.filter-panel__toggle[aria-expanded='true'] { border-color: var(--color-brand-300); background: var(--color-brand-50); }
.filter-panel__chevron { transition: transform var(--motion-fast); }
.filter-panel__toggle[aria-expanded='true'] .filter-panel__chevron { transform: rotate(180deg); }
.filter-panel__count { display: grid; min-width: 1.35rem; height: 1.35rem; place-items: center; padding-inline: .2rem; border-radius: var(--radius-pill); background: var(--color-brand-700); color: var(--color-white); font-size: var(--text-xs); }
.filter-panel__fields { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: var(--space-3); min-width: 0; margin: 1rem 0 0; padding: 1rem 0 0; border: 0; border-top: 1px solid var(--color-border); }
.filter-panel label { display: grid; align-content: start; gap: 0.4rem; min-width: 0; }
.filter-panel__selection { display: flex; align-items: center; flex-wrap: wrap; gap: .65rem; margin: .75rem 0 0; color: var(--color-brand-700); font-size: var(--text-sm); }
.filter-panel__selection button { border: 0; background: transparent; color: var(--color-text-secondary); cursor: pointer; text-decoration: underline; text-underline-offset: 0.2em; }
.filter-panel label > span { color: var(--color-text-secondary); font-size: var(--text-xs); font-weight: 700; }
.filter-panel input,
.filter-panel select { width: 100%; min-width: 0; min-height: 2.8rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); outline: 0; background: var(--color-surface); color: var(--color-text-primary); padding: 0.65rem 0.75rem; }
.filter-panel input:focus,
.filter-panel select:focus { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); }
.filter-panel__search { display: grid; flex: 1; min-width: 0; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: .45rem; border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-bg); color: var(--color-brand-600); padding: 0 .7rem; }
.filter-panel__search:focus-within { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); }
.filter-panel__search input { border: 0; border-radius: 0; background: transparent; box-shadow: none; padding-inline: .2rem; }
.filter-panel__search input:focus { box-shadow: none; }
.direction-toggle { grid-column: 1 / 2; display: flex !important; min-height: 2.8rem; align-items: center; align-self: end; gap: 0.55rem !important; }
.direction-toggle input { width: 1.15rem; min-height: 1.15rem; accent-color: var(--color-brand-700); }
.filter-panel__actions { grid-column: 2 / -1; display: flex; align-items: end; justify-content: flex-end; gap: var(--space-2); }
.result-summary { min-height: 1.5rem; margin: var(--space-6) 0 var(--space-4); color: var(--color-text-secondary); font-size: var(--text-sm); }
.game-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-5); }

@media (max-width: 959px) {
  .filter-panel__fields { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .filter-panel__actions { grid-column: 1 / -1; }
  .game-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 719px) {
  .filter-panel__fields { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .direction-toggle { grid-column: auto; }
  .game-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); }
}

@media (max-width: 439px) {
  .filter-panel__bar { align-items: stretch; flex-direction: column; }
  .filter-panel__buttons :deep(.base-button) { flex: 1; }
  .filter-panel__actions {
    justify-content: stretch;
  }
  .filter-panel__actions :deep(.base-button) {
    flex: 1;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .filter-panel__fields { animation: refinements-arrive 180ms ease-out; }
  @keyframes refinements-arrive { from { opacity: .45; transform: translateY(-.35rem); } to { opacity: 1; transform: none; } }
}
</style>
