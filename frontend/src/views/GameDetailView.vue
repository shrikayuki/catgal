<script setup lang="ts">
import { encodePublicId, gameRoute, readPublicId } from '../utils/navigation'
import {
  CalendarDays,
  Download,
  Eye,
  Heart,
  MessageCircle,
  MonitorSmartphone,
  RefreshCw,
  Star,
} from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getGame, getGameResources, getGameReviews } from '../api/games'
import type { PageData } from '../api/types'
import type { GameContentTab, GameResource, GameReview, GameSummary } from '../types/game'
import { formatCompactNumber, formatDate, getPlatformLabel, getPlayStatusLabel, getRecommendLabel } from '../utils/format'
import BaseButton from '../components/ui/BaseButton.vue'
import UserAvatar from '../components/ui/UserAvatar.vue'
import CoverImage from '../components/content/CoverImage.vue'
import GameExternalSources from '../components/content/GameExternalSources.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import ResourceAccessDialog from '../components/content/ResourceAccessDialog.vue'
import ContentLikeButton from '../components/content/ContentLikeButton.vue'
import type { LikeState } from '../utils/like-state'
import FavoriteGameDialog from '../components/content/FavoriteGameDialog.vue'
import GameDiscussion from '../components/content/GameDiscussion.vue'
import ReviewComposer from '../components/content/ReviewComposer.vue'
import { deleteReview } from '../api/community'
import GameCatalogLinks from '../components/content/GameCatalogLinks.vue'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
const gameId = computed(() => readPublicId(route.params.gameId))
const game = ref<GameSummary | null>(null)
const gameLoading = ref(true)
const gameError = ref('')
const contentLoading = ref(true)
const contentError = ref('')
const resourcesPage = ref<PageData<GameResource> | null>(null)
const reviewsPage = ref<PageData<GameReview> | null>(null)
const contentBusy = ref(false)
const favoriteOpen = computed(() => auth.isLoggedIn && !gameLoading.value && !gameError.value && !!game.value && route.query.favorite === '1' && !selectedResource.value)
let gameController: AbortController | null = null
let contentController: AbortController | null = null

const tabs = [
  { key: 'resources', label: '资源', icon: Download, count: 'resourceCount' },
  { key: 'reviews', label: '评价', icon: Star, count: 'reviewCount' },
  { key: 'comments', label: '讨论', icon: MessageCircle, count: 'commentCount' },
] as const
const tabGroupId = useId()
const activeTab = computed<GameContentTab>(() => {
  return tabs.find((tab) => tab.key === route.query.tab)?.key ?? 'resources'
})
const currentPage = computed(() => {
  const value = Number(typeof route.query.pageNo === 'string' ? route.query.pageNo : '1')
  return Number.isInteger(value) && value > 0 ? value : 1
})
const activePage = computed(() => {
  if (activeTab.value === 'reviews') return reviewsPage.value
  return resourcesPage.value
})
const selectedResource = computed(() => {
  if (!auth.isLoggedIn || activeTab.value !== 'resources' || gameLoading.value || gameError.value
    || game.value?.id !== gameId.value || contentLoading.value || contentError.value) return null
  const raw = route.query.resource
  const id = readPublicId(raw)
  if (!id) return null
  return resourcesPage.value?.list.find(resource => resource.id === id) ?? null
})

function openResource(resource: GameResource) {
  const target = router.resolve({ query: { ...route.query, tab: 'resources', resource: encodePublicId(resource.id) } }).fullPath
  if (!auth.isLoggedIn) {
    void router.push({ name: 'login', query: { redirect: target } })
    return
  }
  void router.push(target)
}

function closeResource() {
  if (!route.query.resource) return
  void router.replace({ query: { ...route.query, resource: undefined } })
}

function resourceDownloaded() {
  if (game.value) game.value.downloadCount = (game.value.downloadCount ?? 0) + 1
}
function openFavorite() {
  const target = router.resolve({ query: { ...route.query, favorite: '1', resource: undefined } }).fullPath
  void router.push(auth.isLoggedIn ? target : { name: 'login', query: { redirect: target } })
}
function closeFavorite() { void router.replace({ query: { ...route.query, favorite: undefined } }) }
function favoriteChanged(delta: number) { if (game.value) game.value.favoriteCount = Math.max(0, (game.value.favoriteCount ?? 0) + delta) }
async function removeReview(id: number) {
  if (contentBusy.value || !window.confirm('确认删除自己的评价？')) return
  contentBusy.value = true
  try { await deleteReview(id); await loadContent(); notifications.show('内容已删除', 'success') }
  catch (cause) { notifications.show(errorMessage(cause), 'error') }
  finally { contentBusy.value = false }
}

function updateLike(item: { isLike: boolean | null; likeCount: number | null }, state: LikeState) {
  item.isLike = state.isLike
  item.likeCount = state.likeCount
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '数据加载失败，请稍后重试'
}

async function loadGame(): Promise<void> {
  if (!Number.isInteger(gameId.value) || gameId.value <= 0) {
    gameError.value = '这份作品链接暂时无法打开'
    gameLoading.value = false
    return
  }
  gameController?.abort()
  gameController = new AbortController()
  gameLoading.value = true
  gameError.value = ''
  try {
    game.value = await getGame(gameId.value, gameController.signal)
    document.title = `${game.value.name} · Catgal`
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    gameError.value = errorMessage(error)
  } finally {
    gameLoading.value = false
  }
}

async function loadContent(): Promise<void> {
  if (!Number.isInteger(gameId.value) || gameId.value <= 0) return
  contentController?.abort()
  if (activeTab.value === 'comments') return
  const request = new AbortController()
  contentController = request
  const requestedGameId = gameId.value
  const requestedPage = currentPage.value
  contentLoading.value = true
  contentError.value = ''
  try {
    if (activeTab.value === 'reviews') {
      const response = await getGameReviews(requestedGameId, requestedPage, 10, request.signal)
      if (request.signal.aborted) return
      reviewsPage.value = response.page
    } else {
      const response = await getGameResources(requestedGameId, requestedPage, 10, request.signal)
      if (request.signal.aborted) return
      resourcesPage.value = response.page
    }
  } catch (error) {
    if (request.signal.aborted) return
    contentError.value = errorMessage(error)
  } finally {
    if (!request.signal.aborted) contentLoading.value = false
  }
}

function selectTab(tab: GameContentTab): void {
  void router.push({ query: { tab: tab === 'resources' ? undefined : tab } })
}

function handleTabKeys(event: KeyboardEvent): void {
  const buttons = (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button[role="tab"]')
  const current = Array.from(buttons).findIndex((button) => button === event.target)
  if (current < 0) return
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    buttons[current]?.click()
    return
  }
  const direction = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  let next = current + direction
  if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = buttons.length - 1
  else if (!direction) return
  event.preventDefault()
  buttons[(next + buttons.length) % buttons.length]?.focus()
}

function changePage(pageNo: number): void {
  void router.push({ query: { ...route.query, pageNo: pageNo > 1 ? String(pageNo) : undefined } })
}

watch(gameId, () => void loadGame(), { immediate: true })
watch([gameId, activeTab, currentPage], () => void loadContent(), { immediate: true })
watch(() => auth.session?.token, (next, previous) => {
  if (next !== previous && route.query.resource) {
    closeResource()
    notifications.show('登录状态已变化，请重新打开资源', 'info')
  }
  if (next !== previous) { if (route.query.favorite) closeFavorite(); void loadContent() }
})

onBeforeUnmount(() => {
  gameController?.abort()
  contentController?.abort()
})
</script>

<template>
  <div class="detail-page">
    <section v-if="gameLoading" class="container detail-status section">
      <StatePanel tone="loading" title="正在翻开作品档案" description="游戏资料和社区内容正在加载。" />
    </section>
    <section v-else-if="gameError || !game" class="container detail-status section">
      <StatePanel tone="error" title="无法打开这份游戏档案" :description="gameError">
        <BaseButton variant="secondary" @click="loadGame">
          <RefreshCw :size="17" aria-hidden="true" />重新加载
        </BaseButton>
      </StatePanel>
    </section>

    <template v-else>
      <section class="game-hero">
        <div class="game-hero__backdrop" aria-hidden="true">
          <img v-if="game.coverUrl" :src="game.coverUrl" alt="" />
        </div>
        <div class="container game-hero__inner">
          <div class="game-hero__cover">
            <CoverImage :src="game.coverUrl" :alt="`${game.name}封面`" eager />
          </div>
          <div class="game-hero__content">
            <h1>{{ game.name }}</h1>
            <div class="game-hero__chips">
              <span v-if="game.releaseDate"><CalendarDays :size="15" aria-hidden="true" />{{ game.releaseDate }}</span>
              <span v-for="platform in game.platforms ?? []" :key="platform"><MonitorSmartphone :size="15" aria-hidden="true" />{{ getPlatformLabel(platform) }}</span>
              <span v-for="language in game.languages ?? []" :key="language">{{ language }}</span>
            </div>
            <p class="game-hero__intro">{{ game.introduction || '这部作品暂时还没有补充简介。' }}</p>
            <GameExternalSources :game-id="game.id" />
            <GameCatalogLinks :game-id="game.id" />
            <div class="game-hero__actions">
              <BaseButton @click="openFavorite">
                <Heart :size="18" aria-hidden="true" />收藏作品
              </BaseButton>
              <BaseButton :to="auth.isLoggedIn ? { query: { tab: 'comments' }, hash: '#discussion' } : { name: 'login', query: { redirect: router.resolve(gameRoute(gameId, { tab: 'comments' }, '#discussion')).fullPath } }" variant="secondary">{{ auth.isLoggedIn ? '参与讨论' : '登录后参与讨论' }}</BaseButton>
            </div>
          </div>
        </div>
      </section>

      <section class="metrics">
        <div class="container metrics__grid">
          <div><Star aria-hidden="true" /><strong>{{ game.rating?.toFixed(1) ?? '—' }}</strong><span>综合评分</span></div>
          <div><Eye aria-hidden="true" /><strong>{{ formatCompactNumber(game.viewCount) }}</strong><span>浏览</span></div>
          <div><Heart aria-hidden="true" /><strong>{{ formatCompactNumber(game.favoriteCount) }}</strong><span>收藏</span></div>
          <div><Download aria-hidden="true" /><strong>{{ formatCompactNumber(game.downloadCount) }}</strong><span>下载</span></div>
          <div><MessageCircle aria-hidden="true" /><strong>{{ formatCompactNumber(game.commentCount) }}</strong><span>评论</span></div>
        </div>
      </section>

      <section id="discussion" class="content-section section">
        <div class="container">
          <div class="tabs" role="tablist" aria-label="游戏内容" @keydown="handleTabKeys">
            <button v-for="tab in tabs" :id="`${tabGroupId}-${tab.key}`" :key="tab.key" type="button" role="tab"
              :aria-selected="activeTab === tab.key" :aria-controls="`${tabGroupId}-panel`" :tabindex="activeTab === tab.key ? 0 : -1" @click="selectTab(tab.key)">
              <component :is="tab.icon" :size="17" aria-hidden="true" />{{ tab.label }}<span>{{ formatCompactNumber(game[tab.count]) }}</span>
            </button>
          </div>
          <div :id="`${tabGroupId}-panel`" :key="activeTab" role="tabpanel" :aria-labelledby="`${tabGroupId}-${activeTab}`" class="content-pane" tabindex="0">
          <BaseButton v-if="activeTab === 'resources' && auth.isLoggedIn && (auth.session?.role === 1 || auth.session?.role === 2)" :to="{ name: 'resource-publish', query: { game: encodePublicId(gameId) } }" variant="secondary" style="margin-bottom:1.5rem">分享这部作品的资源</BaseButton>

          <GameDiscussion v-if="activeTab === 'comments'" :game-id="gameId" :current-page="currentPage" @page-change="changePage" @count-change="game.commentCount = $event" />
          <template v-else>
          <ReviewComposer v-if="activeTab === 'reviews'" :key="`review-${gameId}-${auth.session?.userId ?? 'guest'}`" :game-id="gameId" style="margin-bottom:1.5rem" @saved="loadContent" />

          <div v-if="contentLoading" class="content-loading" aria-label="正在加载内容">
            <div v-for="item in 3" :key="item" />
          </div>
          <StatePanel v-else-if="contentError" tone="error" title="内容加载失败" :description="contentError">
            <BaseButton variant="secondary" @click="loadContent"><RefreshCw :size="17" aria-hidden="true" />重新加载</BaseButton>
          </StatePanel>
          <StatePanel
            v-else-if="!activePage || activePage.list.length === 0"
            :title="activeTab === 'resources' ? '暂时没有可用资源' : '还没有玩家评价'"
            description="欢迎在这里分享资源、评价和感想。"
          />

          <div v-else-if="activeTab === 'resources'" class="content-list">
            <article v-for="resource in resourcesPage?.list ?? []" :key="resource.id" class="resource-card">
              <div class="resource-card__main">
                <UserAvatar :src="resource.avatarUrl" />
                <div>
                  <p class="resource-card__author">{{ resource.username }} · {{ formatDate(resource.createTime) }}</p>
                  <h2>{{ resource.name }}</h2>
                  <p>{{ resource.remark || '发布者没有补充使用说明。' }}</p>
                  <div class="tag-row">
                    <span v-for="item in resource.types ?? []" :key="`type-${item}`">{{ item }}</span>
                    <span v-for="item in resource.languages ?? []" :key="`language-${item}`">{{ item }}</span>
                    <span v-for="item in resource.platforms ?? []" :key="`platform-${item}`">{{ getPlatformLabel(item) }}</span>
                  </div>
                </div>
              </div>
              <div class="resource-card__actions">
                <ContentLikeButton type="resource" :target-id="resource.id" :target-label="resource.name" :liked="resource.isLike" :count="resource.likeCount" @change="updateLike(resource, $event)" @refresh="loadContent" />
                <BaseButton size="small" @click="openResource(resource)"><Download :size="16" aria-hidden="true" />{{ auth.isLoggedIn ? '查看下载入口' : '登录后查看' }}</BaseButton>
              </div>
            </article>
          </div>

          <div v-else-if="activeTab === 'reviews'" class="content-list">
            <article v-for="review in reviewsPage?.list ?? []" :key="review.id" class="review-card">
              <UserAvatar :src="review.avatarUrl" />
              <div class="review-card__content">
                <div class="review-card__header">
                  <div><strong>{{ review.username }}</strong><span>{{ formatDate(review.createTime) }}</span></div>
                  <span class="score"><Star :size="16" fill="currentColor" aria-hidden="true" />{{ review.score ?? '—' }}/10</span>
                </div>
                <div class="tag-row">
                  <span>{{ getRecommendLabel(review.recommendLevel) }}</span>
                  <span>{{ getPlayStatusLabel(review.playStatus) }}</span>
                  <span v-if="review.spoilerLevel && review.spoilerLevel > 1" class="tag-row__warning">含剧透标记</span>
                </div>
                <details v-if="review.spoilerLevel && review.spoilerLevel > 1" class="review-card__notice"><summary>含剧透，点击阅读简评</summary><p class="work-text">{{ review.briefReview || '暂未填写简评' }}</p></details>
                <p v-else class="review-card__notice work-text">{{ review.briefReview || '暂未填写简评' }}</p>
                <ContentLikeButton type="review" :target-id="review.id" :target-label="`${review.username}的评价`" :liked="review.isLike" :count="review.likeCount" @change="updateLike(review, $event)" @refresh="loadContent" />
                <button v-if="review.isMyReview" class="work-danger" :disabled="contentBusy" @click="removeReview(review.id)">删除自己的评价</button>
              </div>
            </article>
          </div>

          <PaginationControl
            v-if="!contentLoading && !contentError && activePage"
            :current-page="currentPage"
            :total-pages="activePage.pages"
            @change="changePage"
          />
          </template>
          </div>
        </div>
      </section>
    </template>
    <ResourceAccessDialog v-if="selectedResource" :key="selectedResource.id" :resource-id="selectedResource.id" :resource-name="selectedResource.name" @close="closeResource" @downloaded="resourceDownloaded" />
    <FavoriteGameDialog v-if="favoriteOpen && game" :key="`favorite-${gameId}-${auth.session?.userId}`" :game-id="gameId" :game-name="game.name" @close="closeFavorite" @changed="favoriteChanged" />
  </div>
</template>

<style scoped>
.detail-status { min-height: 68vh; }
.game-hero { position: relative; overflow: hidden; background: linear-gradient(115deg, #edf5fa, #f8fafd 55%, #f7f0f6); padding: clamp(3rem, 7vw, 6.5rem) 0; }
.game-hero__backdrop { position: absolute; inset: -4rem; opacity: 0.1; filter: blur(45px) saturate(0.8); transform: scale(1.08); }
.game-hero__backdrop img { width: 100%; height: 100%; object-fit: cover; }
.game-hero__inner { position: relative; display: grid; grid-template-columns: minmax(14rem, 22rem) minmax(0, 1fr); align-items: center; gap: clamp(2rem, 6vw, 6rem); }
.game-hero__content { min-width: 0; overflow-wrap: anywhere; }
.game-hero__cover { aspect-ratio: 3 / 4; overflow: hidden; border: 0.55rem solid rgb(255 255 255 / 78%); border-radius: 1.8rem; box-shadow: 0 28px 65px rgb(30 67 101 / 22%); transform: rotate(-1.5deg); }
h1 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: clamp(2.6rem, 6vw, 5rem); font-weight: 500; letter-spacing: -0.055em; line-height: 1.12; }
.game-hero__chips,
.tag-row { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: var(--space-5); }
.game-hero__chips span,
.tag-row span { display: inline-flex; align-items: center; gap: 0.35rem; border-radius: var(--radius-pill); background: rgb(255 255 255 / 78%); color: var(--color-brand-700); font-size: var(--text-xs); font-weight: 700; padding: 0.45rem 0.7rem; }
.game-hero__intro { max-width: 48rem; margin: 1.5rem 0 0; color: var(--color-text-secondary); font-size: var(--text-lg); line-height: 1.85; }
.game-hero__actions { display: flex; flex-wrap: wrap; gap: var(--space-3); margin-top: var(--space-6); }
.metrics { border-block: 1px solid var(--color-border); background: var(--color-surface); }
.metrics__grid { display: grid; grid-template-columns: repeat(5, 1fr); }
.metrics__grid > div { display: grid; grid-template-columns: auto 1fr; grid-template-rows: auto auto; align-items: center; gap: 0.1rem 0.75rem; border-right: 1px solid var(--color-border); padding: var(--space-6); }
.metrics__grid > div:last-child { border-right: 0; }
.metrics svg { grid-row: 1 / 3; color: var(--color-brand-500); }
.metrics strong { color: var(--color-text-primary); font-size: var(--text-xl); }
.metrics span { color: var(--color-text-muted); font-size: var(--text-xs); }
.content-section { min-height: 34rem; scroll-margin-top: 5rem; background: var(--color-bg); }
.tabs { display: grid; width: min(100%, 34rem); grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .3rem; margin-bottom: var(--space-6); padding: .35rem; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface-soft); }
.tabs button { display: flex; min-width: 0; min-height: 3rem; align-items: center; justify-content: center; gap: .45rem; border: 1px solid transparent; border-radius: var(--radius-md); background: transparent; color: var(--color-text-secondary); font-size: var(--text-sm); font-weight: 700; padding: .55rem .25rem; cursor: pointer; transition: background var(--motion-fast), color var(--motion-fast), box-shadow var(--motion-fast); white-space: nowrap; }
.tabs button span { border-radius: var(--radius-pill); background: var(--color-brand-50); color: var(--color-brand-800); font-size: .7rem; padding: .12rem .36rem; }
.tabs button:hover { color: var(--color-brand-800); background: var(--color-brand-50); }
.tabs button[aria-selected='true'] { border-color: var(--color-border); background: var(--color-surface); color: var(--color-brand-900); box-shadow: var(--shadow-sm); }
.tabs button[aria-selected='true'] span { background: var(--color-brand-100); }
.tabs button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: -3px; }
.content-pane { min-width: 0; }
.content-pane:focus-visible { outline: 3px solid var(--color-focus); outline-offset: .3rem; border-radius: var(--radius-lg); }
.content-loading { display: grid; gap: var(--space-3); }
.content-loading div { height: 8rem; border-radius: var(--radius-lg); background: linear-gradient(100deg, var(--color-brand-50) 15%, var(--color-brand-100) 45%, var(--color-brand-50) 75%); background-size: 220% 100%; animation: shimmer 1.5s ease-in-out infinite; }
.content-list { display: grid; gap: var(--space-4); }
.resource-card,
.review-card { display: flex; gap: var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-sm); padding: var(--space-5); }
.resource-card { align-items: center; justify-content: space-between; transition: border-color var(--motion-fast), box-shadow var(--motion-fast); }
.resource-card:focus-within { border-color: var(--color-brand-300); box-shadow: var(--shadow-md); }
.resource-card__main { display: flex; min-width: 0; gap: var(--space-4); }
.resource-card__main > div:last-child { min-width: 0; overflow-wrap: anywhere; }
.resource-card__author { margin: 0; color: var(--color-text-muted); font-size: var(--text-xs); }
.resource-card h2 { margin: 0.35rem 0 0; color: var(--color-text-primary); font-size: var(--text-lg); }
.resource-card h2 + p { margin: 0.5rem 0 0; color: var(--color-text-secondary); line-height: 1.65; }
.resource-card__actions { display: flex; flex: 0 0 auto; align-items: center; gap: var(--space-4); }
.review-card__content { min-width: 0; flex: 1; }
.review-card__header { display: flex; align-items: start; justify-content: space-between; gap: var(--space-4); }
.review-card__header > div { display: grid; gap: 0.2rem; }
.review-card__header span { color: var(--color-text-muted); font-size: var(--text-xs); }
.score { display: inline-flex; align-items: center; gap: 0.35rem; border-radius: var(--radius-pill); background: #fff7e8; color: var(--color-warning) !important; font-weight: 800; padding: 0.45rem 0.7rem; }
.tag-row { margin-top: 0.8rem; }
.tag-row span { background: var(--color-brand-50); }
.tag-row .tag-row__warning { background: #fff3e3; color: var(--color-warning); }
.review-card__notice { margin: 0.9rem 0 0; color: var(--color-text-muted); font-size: var(--text-sm); line-height: 1.65; }

@keyframes shimmer { to { background-position-x: -220%; } }

@media (max-width: 959px) {
  .game-hero__inner { grid-template-columns: 12rem 1fr; gap: var(--space-8); }
  .metrics__grid { grid-template-columns: repeat(3, 1fr); }
  .metrics__grid > div:nth-child(3) { border-right: 0; }
  .metrics__grid > div:nth-child(n + 4) { border-top: 1px solid var(--color-border); }
}

@media (max-width: 639px) {
  .game-hero__inner { grid-template-columns: 1fr; }
  .game-hero__cover { width: min(70vw, 17rem); justify-self: center; }
  .game-hero__content { text-align: center; }
  .game-hero__chips,
  .game-hero__actions { justify-content: center; }
  .game-hero__intro { text-align: left; }
  .metrics__grid { grid-template-columns: repeat(2, 1fr); }
  .metrics__grid > div { border-top: 1px solid var(--color-border); border-right: 1px solid var(--color-border); padding: var(--space-4); }
  .metrics__grid > div:nth-child(-n + 2) { border-top: 0; }
  .metrics__grid > div:nth-child(2n) { border-right: 0; }
  .resource-card { align-items: stretch; flex-direction: column; }
  .resource-card__actions { justify-content: space-between; }
  .review-card { padding: var(--space-4); }
}

@media (max-width: 399px) { .tabs button > svg { display: none; } }

@media (prefers-reduced-motion: no-preference) {
  .content-pane { animation: pane-arrive 220ms ease-out both; }
  @keyframes pane-arrive { from { opacity: .45; transform: translateY(.35rem); } to { opacity: 1; transform: none; } }
}

@media (prefers-reduced-motion: reduce) {
  .content-loading div { animation: none; }
}
</style>
