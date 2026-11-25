<script setup lang="ts">
import { ArrowRight, BookOpen } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import mistReadingRoom from '../../assets/mist-reading-room.webp'
import shinkuRoseTerrace from '../../assets/shinku-rose-terrace.webp'
import type { SiteTheme } from '../../services/theme'
import type { GameSummary } from '../../types/game'
import { gameRoute } from '../../utils/navigation'
import CoverImage from './CoverImage.vue'

type AtmosphereTheme = Exclude<SiteTheme, 'classic'>

defineProps<{ theme: AtmosphereTheme; game?: GameSummary }>()

const illustrations = { mist: mistReadingRoom, shinku: shinkuRoseTerrace }

// 粒子使用固定起点和错开的动画时长，窗口尺寸变化时随画框缩放。
const particles = [
  { x: 13, y: 16, time: 14, offset: 3 },
  { x: 30, y: 74, time: 10, offset: 6 },
  { x: 48, y: 28, time: 17, offset: 8 },
  { x: 61, y: 58, time: 12, offset: 3 },
  { x: 77, y: 13, time: 15, offset: 5 },
  { x: 89, y: 44, time: 11, offset: 7 },
  { x: 85, y: 55, time: 19, offset: 13 },
  { x: 58, y: 17, time: 12, offset: 5 },
  { x: 32, y: 6, time: 16, offset: 12 },
  { x: 18, y: 49, time: 13, offset: 4 },
]
</script>

<template>
  <div class="home-atmosphere" :class="`home-atmosphere--${theme}`">
    <div class="home-atmosphere__scene" aria-hidden="true">
      <img class="home-atmosphere__art" :src="illustrations[theme]" alt="" fetchpriority="high" />
      <div class="home-atmosphere__particles">
        <span
          v-for="particle in particles"
          :key="particle.x"
          class="home-atmosphere__particle"
          :style="{ left: `${particle.x}%`, top: `${particle.y}%`, animationDuration: `${particle.time}s`, animationDelay: `-${particle.offset}s` }"
        />
      </div>
    </div>
    <RouterLink v-if="game" :to="gameRoute(game.id)" class="home-atmosphere__game">
      <span class="home-atmosphere__thumb" aria-hidden="true">
        <CoverImage v-if="game.coverUrl" :src="game.coverUrl" alt="" eager />
        <BookOpen v-else :size="21" :stroke-width="1.5" />
      </span>
      <span class="home-atmosphere__title"><small>最近收录</small><strong>{{ game.name }}</strong></span>
      <ArrowRight :size="18" aria-hidden="true" />
    </RouterLink>
  </div>
</template>

<style scoped>
.home-atmosphere {
  --petal-color: #ffe6ec;
  --petal-shadow: #ad546c;
  --petal-drift: -3.2rem;
  --mote-color: #fff9e3;
  --mote-glow: #ffc4dc;
  position: relative;
  isolation: isolate;
  display: grid;
  min-width: 0;
  min-height: clamp(22rem, 36vw, 30.5rem);
  align-items: end;
  overflow: hidden;
  padding: 1.2rem;
  border: 1px solid var(--color-border);
  border-radius: calc(var(--radius-xl) - .45rem);
  background: var(--mist-surface);
}

.home-atmosphere--shinku {
  --petal-color: #ffb3ba;
  --petal-shadow: #a94259;
  --petal-drift: 3.8rem;
  --mote-color: #fff4c9;
  --mote-glow: #ffbc63;
  border-color: #d7a988;
  border-radius: var(--radius-xl);
  background: linear-gradient(150deg, #f5d6bc, #bc7376);
  box-shadow: var(--shadow-lg);
}

.home-atmosphere__scene {
  position: absolute;
  z-index: -1;
  inset: 0;
  overflow: clip;
  pointer-events: none;
}

.home-atmosphere__art {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 56% center;
  animation: atmosphere-sway 26s ease-in-out infinite alternate;
}

.home-atmosphere--shinku .home-atmosphere__art { object-position: 58% center; }

.home-atmosphere__particles { position: absolute; inset: 0; }

.home-atmosphere__particle {
  position: absolute;
  width: .57rem;
  height: .37rem;
  border-radius: 70% 48% 72% 16%;
  background: linear-gradient(125deg, #fff8f6, var(--petal-color) 63%, var(--petal-shadow));
  box-shadow: 0 0 4px var(--petal-color);
  opacity: 0;
  animation: atmosphere-petal linear infinite;
}

.home-atmosphere__particle:nth-child(even) {
  width: .23rem;
  height: .23rem;
  border-radius: 50%;
  background: var(--mote-color);
  box-shadow: 0 0 7px 1px var(--mote-glow);
  animation-name: atmosphere-mote;
  animation-timing-function: ease-in-out;
}

.home-atmosphere__game {
  display: grid;
  width: min(100%, 22rem);
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: .8rem;
  padding: .55rem .9rem .55rem .55rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-md);
  color: var(--color-brand-800);
  text-decoration: none;
  transition: background var(--motion-fast), border-color var(--motion-fast);
}

.home-atmosphere__game:hover { border-color: var(--color-brand-300); background: var(--color-brand-50); }
.home-atmosphere__game > svg { transition: transform var(--motion-fast); }
.home-atmosphere__game:hover > svg, .home-atmosphere__game:focus-visible > svg { transform: translateX(3px); }
.home-atmosphere__thumb { display: grid; width: 2.6rem; height: 2.9rem; overflow: hidden; place-items: center; border-radius: var(--radius-sm); background: var(--color-brand-100); }
.home-atmosphere__thumb :deep(.cover-image__fallback span) { font-size: 1.3rem; }
.home-atmosphere__thumb :deep(.cover-image__fallback svg) { display: none; }
.home-atmosphere__title small, .home-atmosphere__title strong { display: block; }
.home-atmosphere__title small { color: var(--color-text-muted); font-size: var(--text-xs); }
.home-atmosphere__title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@keyframes atmosphere-sway {
  from { transform: scale(1.025); }
  to { transform: scale(1.065) translate3d(.45%, -.6%, 0); }
}

@keyframes atmosphere-petal {
  0% { transform: translate3d(0, -1.4rem, 0) rotate(-35deg) scale(.7); opacity: 0; }
  18%, 74% { opacity: .74; }
  100% { transform: translate3d(var(--petal-drift), 6.4rem, 0) rotate(160deg); opacity: 0; }
}

@keyframes atmosphere-mote {
  0%, 100% { transform: translate3d(0, .75rem, 0) scale(.65); opacity: 0; }
  55% { transform: translate3d(.7rem, -.8rem, 0) scale(1.2); opacity: .9; }
}

@media (max-width: 767px) {
  .home-atmosphere { min-height: 0; aspect-ratio: 16 / 10; padding: .7rem; }
  .home-atmosphere__particle:nth-child(n+9) { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .home-atmosphere__art { animation: none; }
  .home-atmosphere__particles { display: none; }
  .home-atmosphere__game > svg { transition: none; }
  .home-atmosphere__game:hover > svg, .home-atmosphere__game:focus-visible > svg { transform: none; }
}
</style>
