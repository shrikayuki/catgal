<script setup lang="ts">
import { ArrowRight, BookOpen, Layers3, RefreshCw, Search, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getGames, getRandomGame } from '../api/games'
import type { ApiError } from '../api/types'
import type { GameSummary } from '../types/game'
import BaseButton from '../components/ui/BaseButton.vue'
import GameCard from '../components/content/GameCard.vue'
import GameCardSkeleton from '../components/content/GameCardSkeleton.vue'
import SectionHeading from '../components/ui/SectionHeading.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useNotificationStore } from '../stores/notifications'

const router = useRouter()
const notifications = useNotificationStore()
const keyword = ref('')
const games = ref<GameSummary[]>([])
const loading = ref(true)
const loadError = ref('')
const randomLoading = ref(false)
let gamesController: AbortController | null = null

function getErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  return '数据加载失败，请稍后重试'
}

async function loadGames(): Promise<void> {
  gamesController?.abort()
  gamesController = new AbortController()
  loading.value = true
  loadError.value = ''
  try {
    const page = await getGames(
      { pageNo: 1, pageSize: 8, sortBy: 'create_time', isAsc: false },
      gamesController.signal,
    )
    games.value = page.list ?? []
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    loadError.value = getErrorMessage(error as ApiError)
  } finally {
    loading.value = false
  }
}

function searchGames(): void {
  const name = keyword.value.trim()
  void router.push({ name: 'games', query: name ? { name } : {} })
}

async function visitRandomGame(): Promise<void> {
  if (randomLoading.value) return
  randomLoading.value = true
  try {
    const game = await getRandomGame()
    await router.push({ name: 'game-detail', params: { gameId: game.id } })
  } catch (error) {
    notifications.show(getErrorMessage(error), 'error')
  } finally {
    randomLoading.value = false
  }
}

onMounted(() => void loadGames())
onBeforeUnmount(() => gamesController?.abort())
</script>

<template>
  <div>
    <section class="hero">
      <div class="container hero__grid">
        <div class="hero__content">
          <p class="hero__eyebrow">GALGAME RESOURCE COMMUNITY</p>
          <h1>把喜欢的故事，<br /><em>安静地收藏在这里。</em></h1>
          <p class="hero__lead">
            发现游戏、整理资源、记录评价，也和同好分享那些让人久久难忘的片刻。
          </p>

          <form class="hero-search" role="search" @submit.prevent="searchGames">
            <Search :size="20" aria-hidden="true" />
            <label class="sr-only" for="hero-search">搜索游戏名称</label>
            <input id="hero-search" v-model="keyword" type="search" placeholder="输入游戏名称，例如 Summer Pockets" />
            <button type="submit" aria-label="搜索">
              <ArrowRight :size="20" aria-hidden="true" />
            </button>
          </form>

          <div class="hero__actions">
            <BaseButton to="/games" size="large">
              浏览游戏库
              <ArrowRight :size="18" aria-hidden="true" />
            </BaseButton>
            <BaseButton variant="secondary" size="large" :loading="randomLoading" @click="visitRandomGame">
              <Sparkles :size="18" aria-hidden="true" />
              随机邂逅
            </BaseButton>
          </div>
        </div>

        <div class="editorial-art" aria-label="蓝白画册风格的抽象装饰插画">
          <span class="editorial-art__index">ARCHIVE / 01</span>
          <span class="editorial-art__moon" />
          <span class="editorial-art__sheet editorial-art__sheet--one" />
          <span class="editorial-art__sheet editorial-art__sheet--two" />
          <span class="editorial-art__flower editorial-art__flower--one">✦</span>
          <span class="editorial-art__flower editorial-art__flower--two">✧</span>
          <div class="editorial-art__copy">
            <small>かけがえのない物語を</small>
            <strong>物語の余韻を、<br />ひとつずつ。</strong>
            <span>Stories worth keeping.</span>
          </div>
        </div>
      </div>
    </section>

    <section id="archive-preview" class="archive section">
      <div class="container">
        <SectionHeading
          eyebrow="Game Archive"
          title="游戏档案"
          description="直接来自当前 Catgal 数据库的已发布作品，不使用前端模拟数据。"
        >
          <template #action>
            <BaseButton to="/games" variant="secondary" size="small">
              查看全部
              <ArrowRight :size="16" aria-hidden="true" />
            </BaseButton>
          </template>
        </SectionHeading>

        <div v-if="loading" class="game-grid" aria-label="正在加载游戏">
          <GameCardSkeleton v-for="item in 4" :key="item" />
        </div>
        <StatePanel
          v-else-if="loadError"
          tone="error"
          title="暂时无法取得游戏档案"
          :description="loadError"
        >
          <BaseButton variant="secondary" @click="loadGames">
            <RefreshCw :size="17" aria-hidden="true" />
            重新加载
          </BaseButton>
        </StatePanel>
        <StatePanel
          v-else-if="games.length === 0"
          title="还没有已发布的游戏"
          description="这里会展示后端返回的真实游戏数据。"
        />
        <div v-else class="game-grid">
          <GameCard v-for="game in games" :key="game.id" :game="game" />
        </div>
      </div>
    </section>

    <section id="community-preview" class="principles section">
      <div class="container principles__grid">
        <article>
          <span><BookOpen aria-hidden="true" /></span>
          <h2>作品档案</h2>
          <p>封面、语言与平台信息保持清晰层级，让内容本身成为视觉中心。</p>
        </article>
        <article>
          <span><Layers3 aria-hidden="true" /></span>
          <h2>真实资源</h2>
          <p>只展示后端真实返回的数据；空内容、加载失败与权限不足都有明确状态。</p>
        </article>
        <article>
          <span><ShieldCheck aria-hidden="true" /></span>
          <h2>克制互动</h2>
          <p>收藏、评价和讨论不会喧宾夺主，也不会仅依赖隐藏按钮判断用户权限。</p>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.hero { position: relative; overflow: hidden; padding: clamp(3.5rem, 8vw, 7.5rem) 0 clamp(4rem, 9vw, 8rem); }
.hero::before { position: absolute; inset: 0; background: radial-gradient(circle at 8% 18%, rgb(217 167 195 / 16%), transparent 24rem), radial-gradient(circle at 86% 36%, rgb(101 168 209 / 18%), transparent 27rem), linear-gradient(180deg, #f8fafd 0%, #f4f8fb 100%); content: ''; pointer-events: none; }
.hero__grid { position: relative; display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(23rem, 0.95fr); align-items: center; gap: clamp(2rem, 6vw, 6rem); }
.hero__content { max-width: 43rem; }
.hero__eyebrow { margin: 0 0 1.1rem; color: var(--color-accent-violet); font-size: var(--text-xs); font-weight: 800; letter-spacing: 0.2em; }
h1 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: clamp(2.8rem, 6vw, 5.7rem); font-weight: 500; letter-spacing: -0.065em; line-height: 1.13; }
h1 em { color: var(--color-brand-600); font-style: normal; }
.hero__lead { max-width: 36rem; margin: 1.6rem 0 0; color: var(--color-text-secondary); font-size: var(--text-lg); line-height: 1.85; }
.hero-search { display: grid; max-width: 37rem; min-height: 3.7rem; grid-template-columns: auto 1fr auto; align-items: center; gap: 0.7rem; margin-top: 2rem; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--color-surface); box-shadow: var(--shadow-md); color: var(--color-text-muted); padding: 0.35rem 0.4rem 0.35rem 1.15rem; }
.hero-search:focus-within { border-color: var(--color-brand-500); box-shadow: 0 0 0 4px var(--color-focus), var(--shadow-md); }
.hero-search input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--color-text-primary); }
.hero-search button { display: grid; width: 2.95rem; height: 2.95rem; place-items: center; border: 0; border-radius: 50%; background: var(--color-brand-700); color: var(--color-white); cursor: pointer; }
.hero-search button:hover { background: var(--color-brand-800); }
.hero-search button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 3px; }
.hero__actions { display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-6); }
.editorial-art { position: relative; min-height: 35rem; overflow: hidden; border: 1px solid rgb(255 255 255 / 85%); border-radius: 2.4rem 2.4rem 2.4rem 7rem; background: linear-gradient(140deg, rgb(255 255 255 / 78%), rgb(224 240 249 / 62%)), repeating-linear-gradient(90deg, transparent 0 28px, rgb(23 59 108 / 3%) 28px 29px); box-shadow: 0 30px 70px rgb(41 78 116 / 14%); }
.editorial-art__index { position: absolute; top: 2rem; left: 2rem; color: var(--color-brand-600); font-size: 0.68rem; font-weight: 800; letter-spacing: 0.18em; }
.editorial-art__moon { position: absolute; top: 4rem; right: -2rem; width: 20rem; height: 20rem; border-radius: 50%; background: linear-gradient(150deg, #fff, #d7edf8 65%, #e9ddeb); box-shadow: inset -2rem -2rem 4rem rgb(102 162 204 / 16%); }
.editorial-art__sheet { position: absolute; display: block; border: 1px solid rgb(255 255 255 / 80%); background: rgb(255 255 255 / 62%); box-shadow: 0 18px 34px rgb(54 93 128 / 10%); transform: rotate(-15deg); }
.editorial-art__sheet--one { top: 7rem; left: 1.5rem; width: 9rem; height: 16rem; border-radius: 1rem; }
.editorial-art__sheet--two { right: 2rem; bottom: 3rem; width: 11rem; height: 15rem; border-radius: 1.5rem; transform: rotate(11deg); }
.editorial-art__flower { position: absolute; color: var(--color-accent-pink); font-size: 5rem; opacity: 0.7; }
.editorial-art__flower--one { top: 5rem; right: 2rem; }
.editorial-art__flower--two { bottom: 4rem; left: 4rem; color: var(--color-brand-400); font-size: 8rem; }
.editorial-art__copy { position: absolute; z-index: 1; right: 2.5rem; bottom: 5rem; left: 3rem; display: grid; gap: 0.65rem; }
.editorial-art__copy small { color: var(--color-accent-violet); font-family: var(--font-display); letter-spacing: 0.12em; }
.editorial-art__copy strong { color: var(--color-brand-900); font-family: var(--font-display); font-size: clamp(2rem, 4vw, 3.4rem); font-weight: 500; line-height: 1.25; }
.editorial-art__copy span { color: var(--color-text-muted); font-size: var(--text-xs); letter-spacing: 0.16em; text-transform: uppercase; }
.archive { background: var(--color-surface); }
.game-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--space-5); margin-top: var(--space-8); }
.archive :deep(.state-panel) { margin-top: var(--space-8); }
.principles { background: var(--color-bg); }
.principles__grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-5); }
.principles article { border-top: 1px solid var(--color-border-strong); padding: var(--space-6) var(--space-2) 0; }
.principles article > span { display: grid; width: 2.8rem; height: 2.8rem; place-items: center; border-radius: 50%; background: var(--color-brand-50); color: var(--color-brand-600); }
.principles h2 { margin: 1.1rem 0 0; color: var(--color-text-primary); font-size: var(--text-lg); }
.principles p { margin: 0.6rem 0 0; color: var(--color-text-secondary); line-height: 1.75; }

@media (max-width: 959px) {
  .hero__grid { grid-template-columns: 1fr; }
  .editorial-art { min-height: 27rem; }
  .game-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 639px) {
  .hero { padding-top: 3rem; }
  h1 { font-size: clamp(2.55rem, 13vw, 4rem); }
  .hero__lead { font-size: var(--text-base); }
  .hero__actions { align-items: stretch; flex-direction: column; }
  .editorial-art { min-height: 24rem; border-radius: 1.8rem 1.8rem 1.8rem 4.5rem; }
  .editorial-art__copy { right: 1.5rem; bottom: 3rem; left: 2rem; }
  .editorial-art__moon { width: 15rem; height: 15rem; }
  .editorial-art__sheet--one { width: 6rem; height: 11rem; }
  .game-grid { gap: var(--space-3); }
  .principles__grid { grid-template-columns: 1fr; gap: var(--space-8); }
}
</style>
