<script setup lang="ts">
import {
  CalendarDays,
  Download,
  Eye,
  Heart,
  MessageCircle,
  MonitorSmartphone,
  RefreshCw,
  Star,
  ThumbsUp,
  UserRound,
} from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getGame, getGameComments, getGameResources, getGameReviews } from '../api/games'
import type { PageData } from '../api/types'
import type { GameComment, GameContentTab, GameResource, GameReview, GameSummary } from '../types/game'
import { formatCompactNumber, formatDate, getPlatformLabel, getPlayStatusLabel, getRecommendLabel } from '../utils/format'
import BaseButton from '../components/ui/BaseButton.vue'
import CoverImage from '../components/content/CoverImage.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
import StatePanel from '../components/feedback/StatePanel.vue'

const route = useRoute()
const router = useRouter()
const gameId = computed(() => Number(route.params.gameId))
const game = ref<GameSummary | null>(null)
const gameLoading = ref(true)
const gameError = ref('')
const contentLoading = ref(true)
const contentError = ref('')
const resourcesPage = ref<PageData<GameResource> | null>(null)
const reviewsPage = ref<PageData<GameReview> | null>(null)
const commentsPage = ref<PageData<GameComment> | null>(null)
let gameController: AbortController | null = null
let contentController: AbortController | null = null

const validTabs: GameContentTab[] = ['resources', 'reviews', 'comments']
const activeTab = computed<GameContentTab>(() => {
  const tab = typeof route.query.tab === 'string' ? route.query.tab : 'resources'
  return validTabs.includes(tab as GameContentTab) ? (tab as GameContentTab) : 'resources'
})
const currentPage = computed(() => {
  const value = Number(typeof route.query.pageNo === 'string' ? route.query.pageNo : '1')
  return Number.isInteger(value) && value > 0 ? value : 1
})
const activePage = computed(() => {
  if (activeTab.value === 'reviews') return reviewsPage.value
  if (activeTab.value === 'comments') return commentsPage.value
  return resourcesPage.value
})

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '数据加载失败，请稍后重试'
}

async function loadGame(): Promise<void> {
  if (!Number.isInteger(gameId.value) || gameId.value <= 0) {
    gameError.value = '游戏编号无效'
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
  contentController = new AbortController()
  contentLoading.value = true
  contentError.value = ''
  try {
    if (activeTab.value === 'reviews') {
      const response = await getGameReviews(gameId.value, currentPage.value, 10, contentController.signal)
      reviewsPage.value = response.page
    } else if (activeTab.value === 'comments') {
      const response = await getGameComments(gameId.value, currentPage.value, 10, contentController.signal)
      commentsPage.value = response.page
    } else {
      const response = await getGameResources(gameId.value, currentPage.value, 10, contentController.signal)
      resourcesPage.value = response.page
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    contentError.value = errorMessage(error)
  } finally {
    contentLoading.value = false
  }
}

function selectTab(tab: GameContentTab): void {
  void router.push({ query: { tab: tab === 'resources' ? undefined : tab } })
}

function changePage(pageNo: number): void {
  void router.push({ query: { ...route.query, pageNo: pageNo > 1 ? String(pageNo) : undefined } })
}

watch(gameId, () => void loadGame(), { immediate: true })
watch([gameId, activeTab, currentPage], () => void loadContent(), { immediate: true })

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
            <p class="game-hero__eyebrow">GAME ARCHIVE · NO. {{ game.id }}</p>
            <h1>{{ game.name }}</h1>
            <div class="game-hero__chips">
              <span v-if="game.releaseDate"><CalendarDays :size="15" aria-hidden="true" />{{ game.releaseDate }}</span>
              <span v-for="platform in game.platforms ?? []" :key="platform"><MonitorSmartphone :size="15" aria-hidden="true" />{{ getPlatformLabel(platform) }}</span>
              <span v-for="language in game.languages ?? []" :key="language">{{ language }}</span>
            </div>
            <p class="game-hero__intro">{{ game.introduction || '这部作品暂时还没有补充简介。' }}</p>
            <div class="game-hero__actions">
              <BaseButton to="/login">
                <Heart :size="18" aria-hidden="true" />收藏作品
              </BaseButton>
              <BaseButton to="/login" variant="secondary">登录后参与讨论</BaseButton>
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

      <section class="content-section section">
        <div class="container">
          <div class="tabs" role="tablist" aria-label="游戏内容">
            <button role="tab" :aria-selected="activeTab === 'resources'" :class="{ active: activeTab === 'resources' }" @click="selectTab('resources')">资源 <span>{{ game.resourceCount ?? 0 }}</span></button>
            <button role="tab" :aria-selected="activeTab === 'reviews'" :class="{ active: activeTab === 'reviews' }" @click="selectTab('reviews')">评价 <span>{{ game.reviewCount ?? 0 }}</span></button>
            <button role="tab" :aria-selected="activeTab === 'comments'" :class="{ active: activeTab === 'comments' }" @click="selectTab('comments')">讨论 <span>{{ game.commentCount ?? 0 }}</span></button>
          </div>

          <div v-if="contentLoading" class="content-loading" aria-label="正在加载内容">
            <div v-for="item in 3" :key="item" />
          </div>
          <StatePanel v-else-if="contentError" tone="error" title="内容加载失败" :description="contentError">
            <BaseButton variant="secondary" @click="loadContent"><RefreshCw :size="17" aria-hidden="true" />重新加载</BaseButton>
          </StatePanel>
          <StatePanel
            v-else-if="!activePage || activePage.list.length === 0"
            :title="activeTab === 'resources' ? '暂时没有可用资源' : activeTab === 'reviews' ? '还没有玩家评价' : '还没有人留下评论'"
            description="这里不会使用模拟内容填充；有真实数据后会自动显示。"
          />

          <div v-else-if="activeTab === 'resources'" class="content-list">
            <article v-for="resource in resourcesPage?.list ?? []" :key="resource.id" class="resource-card">
              <div class="resource-card__main">
                <div class="avatar"><UserRound :size="19" aria-hidden="true" /></div>
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
                <span><ThumbsUp :size="16" aria-hidden="true" />{{ formatCompactNumber(resource.likeCount) }}</span>
                <BaseButton to="/login" size="small">登录后查看</BaseButton>
              </div>
            </article>
          </div>

          <div v-else-if="activeTab === 'reviews'" class="content-list">
            <article v-for="review in reviewsPage?.list ?? []" :key="review.id" class="review-card">
              <div class="avatar avatar--image">
                <img v-if="review.avatarUrl" :src="review.avatarUrl" :alt="`${review.username}头像`" loading="lazy" />
                <UserRound v-else :size="19" aria-hidden="true" />
              </div>
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
                <p class="review-card__notice">当前后端评价响应没有返回评价正文，因此这里只展示真实评分和游玩状态。</p>
              </div>
            </article>
          </div>

          <div v-else class="content-list">
            <article v-for="comment in commentsPage?.list ?? []" :key="comment.id" class="comment-card">
              <div class="avatar avatar--image">
                <img v-if="comment.avatarUrl" :src="comment.avatarUrl" :alt="`${comment.username}头像`" loading="lazy" />
                <UserRound v-else :size="19" aria-hidden="true" />
              </div>
              <div class="comment-card__content">
                <div class="comment-card__header"><strong>{{ comment.username }}</strong><span>{{ formatDate(comment.createTime) }}</span></div>
                <p>{{ comment.content }}</p>
                <span class="like-count"><ThumbsUp :size="15" aria-hidden="true" />{{ formatCompactNumber(comment.likeCount) }}</span>
              </div>
            </article>
          </div>

          <PaginationControl
            v-if="!contentLoading && !contentError && activePage"
            :current-page="currentPage"
            :total-pages="activePage.pages"
            @change="changePage"
          />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail-status { min-height: 68vh; }
.game-hero { position: relative; overflow: hidden; background: linear-gradient(115deg, #edf5fa, #f8fafd 55%, #f7f0f6); padding: clamp(3rem, 7vw, 6.5rem) 0; }
.game-hero__backdrop { position: absolute; inset: -4rem; opacity: 0.1; filter: blur(45px) saturate(0.8); transform: scale(1.08); }
.game-hero__backdrop img { width: 100%; height: 100%; object-fit: cover; }
.game-hero__inner { position: relative; display: grid; grid-template-columns: minmax(14rem, 22rem) 1fr; align-items: center; gap: clamp(2rem, 6vw, 6rem); }
.game-hero__cover { aspect-ratio: 3 / 4; overflow: hidden; border: 0.55rem solid rgb(255 255 255 / 78%); border-radius: 1.8rem; box-shadow: 0 28px 65px rgb(30 67 101 / 22%); transform: rotate(-1.5deg); }
.game-hero__eyebrow { margin: 0 0 1rem; color: var(--color-accent-violet); font-size: var(--text-xs); font-weight: 800; letter-spacing: 0.18em; }
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
.content-section { min-height: 34rem; background: var(--color-bg); }
.tabs { display: flex; overflow-x: auto; gap: var(--space-2); margin-bottom: var(--space-8); border-bottom: 1px solid var(--color-border); }
.tabs button { display: inline-flex; min-width: max-content; align-items: center; gap: 0.5rem; border: 0; border-bottom: 3px solid transparent; background: transparent; color: var(--color-text-secondary); font-weight: 800; padding: 0.9rem 1.1rem; cursor: pointer; }
.tabs button span { border-radius: var(--radius-pill); background: var(--color-brand-50); color: var(--color-brand-700); font-size: 0.7rem; padding: 0.25rem 0.5rem; }
.tabs button.active { border-bottom-color: var(--color-brand-700); color: var(--color-brand-800); }
.tabs button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: -3px; }
.content-loading { display: grid; gap: var(--space-3); }
.content-loading div { height: 8rem; border-radius: var(--radius-lg); background: linear-gradient(100deg, var(--color-brand-50) 15%, var(--color-brand-100) 45%, var(--color-brand-50) 75%); background-size: 220% 100%; animation: shimmer 1.5s ease-in-out infinite; }
.content-list { display: grid; gap: var(--space-4); }
.resource-card,
.review-card,
.comment-card { display: flex; gap: var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-sm); padding: var(--space-5); }
.resource-card { align-items: center; justify-content: space-between; }
.resource-card__main { display: flex; min-width: 0; gap: var(--space-4); }
.resource-card__author { margin: 0; color: var(--color-text-muted); font-size: var(--text-xs); }
.resource-card h2 { margin: 0.35rem 0 0; color: var(--color-text-primary); font-size: var(--text-lg); }
.resource-card h2 + p { margin: 0.5rem 0 0; color: var(--color-text-secondary); line-height: 1.65; }
.resource-card__actions { display: flex; flex: 0 0 auto; align-items: center; gap: var(--space-4); }
.resource-card__actions > span,
.like-count { display: inline-flex; align-items: center; gap: 0.35rem; color: var(--color-text-muted); font-size: var(--text-xs); }
.avatar { display: grid; width: 2.7rem; height: 2.7rem; flex: 0 0 auto; place-items: center; overflow: hidden; border-radius: 50%; background: var(--color-brand-50); color: var(--color-brand-600); }
.avatar img { width: 100%; height: 100%; object-fit: cover; }
.review-card__content,
.comment-card__content { min-width: 0; flex: 1; }
.review-card__header,
.comment-card__header { display: flex; align-items: start; justify-content: space-between; gap: var(--space-4); }
.review-card__header > div { display: grid; gap: 0.2rem; }
.review-card__header span,
.comment-card__header span { color: var(--color-text-muted); font-size: var(--text-xs); }
.score { display: inline-flex; align-items: center; gap: 0.35rem; border-radius: var(--radius-pill); background: #fff7e8; color: var(--color-warning) !important; font-weight: 800; padding: 0.45rem 0.7rem; }
.tag-row { margin-top: 0.8rem; }
.tag-row span { background: var(--color-brand-50); }
.tag-row .tag-row__warning { background: #fff3e3; color: var(--color-warning); }
.review-card__notice { margin: 0.9rem 0 0; color: var(--color-text-muted); font-size: var(--text-sm); line-height: 1.65; }
.comment-card__content > p { margin: 0.8rem 0; color: var(--color-text-primary); line-height: 1.75; white-space: pre-wrap; word-break: break-word; }

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
  .review-card,
  .comment-card { padding: var(--space-4); }
}

@media (prefers-reduced-motion: reduce) {
  .content-loading div { animation: none; }
}
</style>
