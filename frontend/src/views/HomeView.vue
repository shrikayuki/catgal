<script setup lang="ts">
import { ArrowRight, BookOpen, MessageCircle, RefreshCw, Search, Sparkles, Tags } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { gameRoute } from '../utils/navigation'
import CoverImage from '../components/content/CoverImage.vue'
import HomeAtmosphere from '../components/content/HomeAtmosphere.vue'
import { getGames, getRandomGame } from '../api/games'
import type { ApiError } from '../api/types'
import type { GameSummary } from '../types/game'
import BaseButton from '../components/ui/BaseButton.vue'
import GameCard from '../components/content/GameCard.vue'
import GameCardSkeleton from '../components/content/GameCardSkeleton.vue'
import SectionHeading from '../components/ui/SectionHeading.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useNotificationStore } from '../stores/notifications'
import { useTheme } from '../services/theme'

const router = useRouter()
const notifications = useNotificationStore()
const { theme } = useTheme()
const isMist = computed(() => theme.value === 'mist')
const atmosphereTheme = computed(() => theme.value === 'classic' ? undefined : theme.value)
const keyword = ref('')
const games = ref<GameSummary[]>([])
const featuredGame = computed(() => games.value[0])
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
  void router.push({ name: 'games', query: name ? { keyword: name } : {} })
}

async function visitRandomGame(): Promise<void> {
  if (randomLoading.value) return
  randomLoading.value = true
  try {
    const game = await getRandomGame()
    await router.push(gameRoute(game.id))
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
  <div :class="{ 'home--mist': isMist }">
    <section class="hero" :class="{ 'hero--shinku': theme === 'shinku', 'hero--mist': isMist }">
      <div class="container hero__grid">
        <div class="hero__content">
          <p v-if="isMist" class="mist-intro"><span aria-hidden="true" />Galgame 资源社区</p>
          <h1>{{ isMist ? '翻开故事，' : '把喜欢的故事，' }}<br /><em>{{ isMist ? '遇见下一份心动。' : '安静地收藏在这里。' }}</em></h1>
          <p class="hero__lead">
            {{ isMist ? '找一部想玩的游戏，看看资源和评价，再和同好聊聊。' : '发现游戏、整理资源、记录评价，也和同好分享那些让人久久难忘的片刻。' }}
          </p>

          <form class="hero-search" role="search" @submit.prevent="searchGames">
            <Search :size="20" aria-hidden="true" />
            <label class="sr-only" for="hero-search">搜索游戏名称</label>
            <input id="hero-search" v-model="keyword" type="search" placeholder="搜索想读的故事" />
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

        <HomeAtmosphere v-if="atmosphereTheme" :key="atmosphereTheme" :theme="atmosphereTheme" :game="featuredGame" />
        <div v-else class="hero-showcase">
          <template v-if="featuredGame">
            <div v-if="games[2]" class="hero-showcase__back hero-showcase__back--far" aria-hidden="true">
              <CoverImage :src="games[2].coverUrl" alt="" />
            </div>
            <div v-if="games[1]" class="hero-showcase__back" aria-hidden="true">
              <CoverImage :src="games[1].coverUrl" alt="" />
            </div>
            <RouterLink :to="gameRoute(featuredGame.id)" class="featured-story">
              <div class="featured-story__cover">
                <CoverImage :src="featuredGame.coverUrl" :alt="`${featuredGame.name}封面`" eager />
              </div>
              <div class="featured-story__caption">
                <div><span>最近收录</span><h2>{{ featuredGame.name }}</h2></div>
                <ArrowRight :size="20" aria-hidden="true" />
              </div>
            </RouterLink>
          </template>
          <div v-else class="hero-showcase__empty" :aria-busy="loading">
            <BookOpen :size="48" aria-hidden="true" />
            <p>{{ loading ? '正在翻开故事…' : '下一段故事，等你发现。' }}</p>
          </div>
        </div>
      </div>
      <nav v-if="isMist" class="container mist-links" aria-label="探索社区">
        <RouterLink to="/catalog/tags"><Tags :size="19" aria-hidden="true" />标签目录<ArrowRight :size="16" aria-hidden="true" /></RouterLink>
        <RouterLink to="/community"><MessageCircle :size="19" aria-hidden="true" />社区讨论<ArrowRight :size="16" aria-hidden="true" /></RouterLink>
        <RouterLink to="/ai"><Sparkles :size="19" aria-hidden="true" />AI 聊天伙伴<ArrowRight :size="16" aria-hidden="true" /></RouterLink>
      </nav>
    </section>

    <section id="archive-preview" class="archive section">
      <div class="container">
        <SectionHeading
          title="新收录的故事"
          :description="isMist ? undefined : '翻一翻最近加入的作品，也许就有你的下一份心动。'"
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
          description="故事还在整理中，过些时候再来看看吧。"
        />
        <div v-else class="game-grid">
          <GameCard v-for="game in games" :key="game.id" :game="game" />
        </div>
      </div>
    </section>

    <section v-if="!isMist" class="explore section" aria-label="探索社区">
      <div class="container explore__grid">
        <RouterLink to="/games" class="explore-card">
          <BookOpen :size="25" aria-hidden="true" />
          <div><h2>寻找下一部作品</h2><p>按语言、平台和发售年份，找到合心意的故事。</p></div>
          <ArrowRight :size="18" aria-hidden="true" />
        </RouterLink>
        <RouterLink to="/community" class="explore-card">
          <MessageCircle :size="25" aria-hidden="true" />
          <div><h2>聊聊故事的余韵</h2><p>读一段评价，写下感想，遇见有共鸣的同好。</p></div>
          <ArrowRight :size="18" aria-hidden="true" />
        </RouterLink>
        <RouterLink to="/ai" class="explore-card">
          <Sparkles :size="25" aria-hidden="true" />
          <div><h2>和聊天伙伴说说话</h2><p>分享你的喜好，一起发现值得一读的作品。</p></div>
          <ArrowRight :size="18" aria-hidden="true" />
        </RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.hero.hero--shinku {
  position: relative;
  isolation: isolate;
  padding-block: clamp(2.75rem, 5vw, 4.25rem);
  background:
    radial-gradient(ellipse at 85% 120%, #f4d3ad, transparent 52%),
    radial-gradient(ellipse at 89% 10%, #f2d6d6, transparent 49%),
    linear-gradient(108deg, var(--color-bg) 28%, #f9ece0 100%);
}
.hero--shinku::before,
.hero--shinku::after {
  position: absolute;
  z-index: -1;
  right: -11rem;
  bottom: -11rem;
  width: clamp(27rem, 60vw, 60rem);
  height: 24rem;
  border: 1px solid rgb(153 101 31 / 20%);
  border-radius: 50%;
  box-shadow: inset 0 1.1rem 2.2rem rgb(255 253 250 / 43%);
  transform: rotate(-25deg);
  pointer-events: none;
  content: '';
}
.hero--shinku::after { right: -9rem; bottom: -12.25rem; opacity: .58; }
.hero--shinku h1 { font-size: clamp(2.15rem, 4.15vw, 3.95rem); line-height: 1.48; letter-spacing: -0.035em; }
.hero--shinku .hero__lead { max-width: 30rem; }
.hero--shinku .hero-search { max-width: 31rem; }

.hero {
  overflow: hidden;
  padding-block: clamp(3rem, 6vw, 6rem);
  background: radial-gradient(ellipse at 80% 25%, #e3edf7 0, transparent 55%), linear-gradient(150deg, #fbfaf8, #f2f6fa);
  border-bottom: 1px solid var(--color-border);
}

.hero__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(19rem, 0.85fr);
  align-items: center;
  gap: clamp(2rem, 5vw, 5rem);
}

.hero__content {
  min-width: 0;
}

h1 {
  margin: 0;
  color: var(--color-brand-900);
  font-family: var(--font-display);
  font-size: clamp(2.5rem, 4.5vw, 4.3rem);
  font-weight: 600;
  letter-spacing: -0.045em;
  line-height: 1.4;
  text-wrap: balance;
}

h1 em {
  color: var(--color-brand-600);
  font-style: normal;
}

.hero__lead {
  max-width: 31rem;
  margin: 1.5rem 0 0;
  color: var(--color-text-secondary);
  font-size: var(--text-base);
  line-height: 2;
}

.hero-search {
  display: grid;
  max-width: 34rem;
  min-height: 3.6rem;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 0.7rem;
  margin-top: 2rem;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
  color: var(--color-text-muted);
  padding: 0.35rem 0.4rem 0.35rem 1.1rem;
}

.hero-search:focus-within {
  border-color: var(--color-brand-500);
  box-shadow: 0 0 0 4px var(--color-focus);
}

.hero-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--color-text-primary);
}

.hero-search button {
  display: grid;
  width: 2.8rem;
  height: 2.8rem;
  place-items: center;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--color-brand-700);
  color: var(--color-white);
  cursor: pointer;
}

.hero-search button:hover {
  background: var(--color-brand-800);
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-6);
}

.hero-showcase {
  position: relative;
  display: grid;
  min-height: 30rem;
  align-items: center;
  padding: 1rem 1.5rem 1rem 0;
  isolation: isolate;
}

.hero-showcase::before {
  position: absolute;
  z-index: -1;
  inset: 14% -15% 0 5%;
  border-radius: 50%;
  background: radial-gradient(ellipse, rgb(133 170 199 / 23%), transparent 70%);
  content: '';
}

.hero-showcase__back {
  position: absolute;
  top: 9%;
  left: 15%;
  width: 56%;
  height: 76%;
  overflow: hidden;
  border: 6px solid white;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  transform: rotate(-12deg);
}

.hero-showcase__back--far {
  top: 15%;
  left: 2%;
  transform: rotate(-21deg);
  opacity: 0.8;
}

.featured-story {
  z-index: 1;
  display: block;
  width: min(77%, 21rem);
  justify-self: end;
  overflow: hidden;
  border: 7px solid white;
  border-radius: var(--radius-lg);
  background: white;
  box-shadow: 0 22px 48px rgb(28 58 90 / 20%);
  text-decoration: none;
  transform: rotate(5deg);
  transition: transform var(--motion-normal), box-shadow var(--motion-normal);
}

.featured-story:hover {
  box-shadow: 0 26px 56px rgb(28 58 90 / 25%);
  transform: rotate(2deg) translateY(-4px);
}

.featured-story__cover {
  aspect-ratio: 4 / 5;
  overflow: hidden;
  border-radius: 0.6rem;
}

.featured-story__caption {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 0.6rem 0.7rem;
}

.featured-story__caption > div {
  min-width: 0;
}

.featured-story__caption > svg {
  flex-shrink: 0;
  color: var(--color-brand-600);
}

.featured-story__caption span {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.featured-story h2 {
  display: -webkit-box;
  overflow: hidden;
  margin: 0.4rem 0 0;
  color: var(--color-brand-800);
  font-size: var(--text-base);
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.hero-showcase__empty {
  display: grid;
  min-height: 23rem;
  place-content: center;
  justify-items: center;
  gap: 1.2rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: rgb(255 255 255 / 65%);
  color: var(--color-brand-600);
}

.hero-showcase__empty p {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-lg);
}

.archive {
  background: var(--color-surface);
}

.game-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-6);
  margin-top: var(--space-8);
}

.archive :deep(.state-panel) {
  margin-top: var(--space-8);
}

.explore {
  padding-block: 2.5rem;
}

.explore__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1rem;
}

.explore-card {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 1.5rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  text-decoration: none;
  transition: border-color var(--motion-fast), box-shadow var(--motion-fast);
}

.explore-card:hover {
  border-color: var(--color-brand-300);
  box-shadow: var(--shadow-sm);
}

.explore-card > svg {
  flex-shrink: 0;
  margin-top: 0.2rem;
  color: var(--color-brand-600);
}

.explore-card > svg:last-child {
  width: 1rem;
  margin-left: auto;
}

.explore-card h2 {
  margin: 0;
  color: var(--color-brand-800);
  font-size: var(--text-base);
  line-height: 1.6;
}

.explore-card p {
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  line-height: 1.85;
}

@media (max-width: 959px) {
  .hero__grid {
    grid-template-columns: minmax(0, 1fr) minmax(16rem, 0.8fr);
    gap: 1.5rem;
  }
  .hero-showcase {
    min-height: 25rem;
    padding-right: 0.5rem;
  }
  .game-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .explore__grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 639px) {
  .hero__grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 2rem;
  }
  h1 {
    font-size: clamp(2rem, 7.7vw, 3rem);
  }
  .hero__lead {
    margin-top: 1rem;
  }
  .hero-search {
    margin-top: 1.5rem;
  }
  .hero__actions {
    gap: 0.65rem;
  }
  .hero__actions :deep(.base-button) {
    flex: 1;
    padding-inline: 0.8rem;
    font-size: var(--text-sm);
  }
  .hero-showcase {
    width: min(100%, 25rem);
    min-height: 24rem;
    justify-self: center;
    padding: 0.5rem 1rem;
  }
  .featured-story {
    width: min(72%, 16rem);
  }
  .hero-showcase__empty {
    min-height: 16rem;
  }
  .game-grid {
    gap: var(--space-3);
  }
  .explore-card {
    padding: 1.25rem;
  }
}

@media (max-width: 767px) {
  .hero--shinku .hero__grid { grid-template-columns: minmax(0, 1fr); gap: 1.75rem; }
  .hero--shinku h1 { font-size: clamp(1.9rem, 6.1vw, 2.7rem); }
  .hero--shinku .hero__lead { max-width: 35rem; }
  .hero--shinku .hero-search { max-width: 35rem; }
  .hero--shinku .home-atmosphere { width: min(100%, 35rem); }
}

@media (prefers-reduced-motion: reduce) {
  .featured-story:hover {
    transform: rotate(5deg);
  }
}

.hero.hero--mist {
  padding-block: clamp(1.4rem, 3.5vw, 3rem) 1.4rem;
  background: radial-gradient(ellipse at 87% -15%, #f1dbe1, transparent 62%), var(--color-bg);
}

.hero--mist .hero__grid {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.12fr);
  align-items: stretch;
  gap: .7rem;
  padding: .7rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-md);
}

.hero--mist .hero__content { align-self: center; padding: clamp(1.25rem, 3vw, 2.7rem); }
.mist-intro { display: flex; align-items: center; gap: .7rem; margin: 0 0 1rem; color: var(--color-text-secondary); font-size: var(--text-sm); }
.mist-intro span { width: 1.65rem; height: 2px; background: var(--color-accent-amber); }
.hero--mist h1 { font-size: clamp(2rem, 4.05vw, 3.65rem); line-height: 1.35; letter-spacing: -.045em; }
.hero--mist .hero__lead { margin-top: 1rem; line-height: 1.8; }
.hero--mist .hero-search { margin-top: 1.5rem; background: var(--color-bg); }
.hero--mist .hero__actions { margin-top: 1rem; }

.mist-links { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .7rem; margin-top: .85rem; }
.mist-links a {
  display: flex;
  min-height: 3.25rem;
  align-items: center;
  gap: .65rem;
  padding: .65rem .95rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-brand-800);
  font-size: var(--text-sm);
  font-weight: 600;
  text-decoration: none;
}
.mist-links a:hover { border-color: var(--color-brand-300); background: var(--color-brand-50); }
.mist-links svg { flex-shrink: 0; color: var(--color-brand-600); }
.mist-links svg:last-child { margin-left: auto; }
.home--mist .archive { padding-top: clamp(1.75rem, 3vw, 2.65rem); }
.home--mist .game-grid { margin-top: 1.45rem; }

@media (max-width: 767px) {
  .hero--mist .hero__grid { grid-template-columns: minmax(0, 1fr); }
  .hero--mist h1 { font-size: clamp(1.75rem, 6.4vw, 2.65rem); }
  .hero--mist .hero__content { padding: 1.1rem .8rem; }
  .hero--mist .hero__lead { margin-top: .75rem; }
}

@media (max-width: 480px) {
  .mist-links { gap: .42rem; }
  .mist-links a { flex-direction: column; justify-content: center; gap: .25rem; padding: .5rem .1rem; font-size: var(--text-xs); text-align: center; }
  .mist-links svg:last-child { display: none; }
}
</style>
