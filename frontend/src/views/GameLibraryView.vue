<script setup lang="ts">
import { RefreshCw, Search, SlidersHorizontal, X } from 'lucide-vue-next'
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
let requestController: AbortController | null = null

const filters = reactive({
  name: '',
  releaseYear: '',
  resourceType: '',
  language: '',
  platform: '',
  sortBy: 'create_time',
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

function syncFiltersFromRoute(): void {
  filters.name = queryString(route.query.name)
  filters.releaseYear = queryString(route.query.releaseYear)
  filters.resourceType = queryString(route.query.resourceType)
  filters.language = queryString(route.query.language)
  filters.platform = queryString(route.query.platform)
  filters.sortBy = queryString(route.query.sortBy) || 'create_time'
  filters.isAsc = queryString(route.query.isAsc) === 'true'
}

function buildRequestQuery(): GamePageQuery {
  return {
    pageNo: currentPage.value,
    pageSize: 12,
    name: queryString(route.query.name) || undefined,
    releaseYear: queryNumber(route.query.releaseYear, 0) || undefined,
    resourceType: queryString(route.query.resourceType) || undefined,
    language: queryString(route.query.language) || undefined,
    platform: queryString(route.query.platform) || undefined,
    sortBy: queryString(route.query.sortBy) || 'create_time',
    isAsc: queryString(route.query.isAsc) === 'true',
  }
}

async function loadGames(): Promise<void> {
  requestController?.abort()
  requestController = new AbortController()
  loading.value = true
  loadError.value = ''
  try {
    page.value = await getGames(buildRequestQuery(), requestController.signal)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    loadError.value = error instanceof Error ? error.message : '游戏列表加载失败'
  } finally {
    loading.value = false
  }
}

function applyFilters(): void {
  const query: Record<string, string> = {}
  if (filters.name.trim()) query.name = filters.name.trim()
  if (filters.releaseYear.trim()) query.releaseYear = filters.releaseYear.trim()
  if (filters.resourceType) query.resourceType = filters.resourceType
  if (filters.language) query.language = filters.language
  if (filters.platform) query.platform = filters.platform
  if (filters.sortBy !== 'create_time') query.sortBy = filters.sortBy
  if (filters.isAsc) query.isAsc = 'true'
  void router.push({ name: 'games', query })
}

function resetFilters(): void {
  void router.push({ name: 'games' })
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
        eyebrow="Game Archive"
        title="游戏库"
        description="搜索、资源条件与排序均使用后端现有查询能力，并同步到当前 URL。"
      />

      <form class="filter-panel" @submit.prevent="applyFilters">
        <div class="filter-panel__search">
          <Search :size="19" aria-hidden="true" />
          <label class="sr-only" for="library-name">搜索游戏名称</label>
          <input id="library-name" v-model="filters.name" type="search" placeholder="搜索游戏名称" />
        </div>

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
            <option value="create_time">最近收录</option>
            <option value="rating">评分</option>
            <option value="view_count">浏览数</option>
            <option value="favorite_count">收藏数</option>
            <option value="release_date">发售日期</option>
          </select>
        </label>
        <label class="direction-toggle">
          <input v-model="filters.isAsc" type="checkbox" />
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
.library-page { min-height: 68vh; }
.filter-panel { display: grid; grid-template-columns: minmax(15rem, 1.8fr) repeat(5, minmax(8rem, 1fr)); gap: var(--space-3); margin-top: var(--space-8); border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: var(--color-surface); box-shadow: var(--shadow-sm); padding: var(--space-5); }
.filter-panel label { display: grid; align-content: start; gap: 0.4rem; min-width: 0; }
.filter-panel label > span { color: var(--color-text-secondary); font-size: var(--text-xs); font-weight: 700; }
.filter-panel input,
.filter-panel select { width: 100%; min-width: 0; min-height: 2.8rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); outline: 0; background: var(--color-surface); color: var(--color-text-primary); padding: 0.65rem 0.75rem; }
.filter-panel input:focus,
.filter-panel select:focus { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); }
.filter-panel__search { display: grid; grid-template-columns: auto 1fr; align-items: end; gap: 0.55rem; border-bottom: 1px solid var(--color-border-strong); color: var(--color-text-muted); padding: 0 0.5rem; }
.filter-panel__search input { border: 0; border-radius: 0; box-shadow: none; }
.filter-panel__search input:focus { box-shadow: none; }
.direction-toggle { grid-column: 1 / 2; display: flex !important; min-height: 2.8rem; align-items: center; align-self: end; gap: 0.55rem !important; }
.direction-toggle input { width: 1.15rem; min-height: 1.15rem; accent-color: var(--color-brand-700); }
.filter-panel__actions { grid-column: -3 / -1; display: flex; align-items: end; justify-content: flex-end; gap: var(--space-2); }
.result-summary { min-height: 1.5rem; margin: var(--space-6) 0 var(--space-4); color: var(--color-text-secondary); font-size: var(--text-sm); }
.game-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-5); }

@media (max-width: 1199px) {
  .filter-panel { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .filter-panel__search { grid-column: span 2; }
  .filter-panel__actions { grid-column: span 2; }
}

@media (max-width: 959px) {
  .game-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 719px) {
  .filter-panel { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .filter-panel__search,
  .filter-panel__actions { grid-column: 1 / -1; }
  .game-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); }
}

@media (max-width: 439px) {
  .filter-panel { grid-template-columns: 1fr; }
  .filter-panel__search,
  .filter-panel__actions { grid-column: auto; }
  .filter-panel__actions { align-items: stretch; flex-direction: column; }
}
</style>
