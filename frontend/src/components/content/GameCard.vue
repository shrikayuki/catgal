<script setup lang="ts">
import { gameRoute } from '../../utils/navigation'
import { Eye, Heart, Star } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { GameSummary } from '../../types/game'
import { formatCompactNumber } from '../../utils/format'
import CoverImage from './CoverImage.vue'

defineProps<{ game: GameSummary }>()
</script>

<template>
  <article class="game-card">
    <RouterLink class="game-card__cover" :to="gameRoute(game.id)" :aria-label="`查看${game.name}详情`">
      <CoverImage :src="game.coverUrl" :alt="`${game.name}封面`" />
      <span v-if="game.rating !== null" class="game-card__rating">
        <Star :size="14" fill="currentColor" aria-hidden="true" />
        {{ game.rating.toFixed(1) }}
      </span>
    </RouterLink>

    <div class="game-card__body">
      <div class="game-card__tags">
        <span v-for="tag in (game.resourceTypes ?? []).slice(0, 2)" :key="tag">{{ tag }}</span>
        <span v-if="game.releaseDate">{{ game.releaseDate.slice(0, 4) }}</span>
      </div>
      <h3><RouterLink :to="gameRoute(game.id)">{{ game.name }}</RouterLink></h3>
      <p>{{ game.introduction || '还没有作品简介，点开看看大家的分享。' }}</p>
      <div class="game-card__meta">
        <span><Eye :size="15" aria-hidden="true" />{{ formatCompactNumber(game.viewCount) }}</span>
        <span><Heart :size="15" aria-hidden="true" />{{ formatCompactNumber(game.favoriteCount) }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.game-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
  transition:
    transform var(--motion-normal),
    border-color var(--motion-normal),
    box-shadow var(--motion-normal);
}

.game-card:hover {
  border-color: var(--color-brand-300);
  box-shadow: var(--shadow-md);
  transform: translateY(-4px);
}

.game-card__cover {
  position: relative;
  display: block;
  aspect-ratio: 4 / 5;
  overflow: hidden;
  background: var(--color-brand-50);
}

.game-card:hover .game-card__cover :deep(img) { transform: scale(1.025); }

.game-card__rating {
  position: absolute;
  right: 0.75rem;
  bottom: 0.75rem;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  border: 1px solid rgb(255 255 255 / 70%);
  border-radius: var(--radius-pill);
  background: rgb(255 255 255 / 92%);
  box-shadow: var(--shadow-sm);
  color: var(--color-warning);
  font-size: var(--text-xs);
  font-weight: 800;
  padding: 0.45rem 0.65rem;
}

.game-card__body { padding: 1.1rem; }
.game-card__tags { display: flex; min-height: 1.5rem; flex-wrap: wrap; gap: 0.4rem; }
.game-card__tags span { border-radius: var(--radius-pill); background: var(--color-brand-50); color: var(--color-brand-700); font-size: 0.68rem; font-weight: 700; padding: 0.32rem 0.55rem; }
h3 { display: -webkit-box; min-height: 3rem; overflow: hidden; margin: 0.75rem 0 0; font-size: var(--text-base); line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow-wrap: anywhere; }
h3 a { color: var(--color-text-primary); text-decoration: none; }
h3 a::after { position: absolute; }
p { display: -webkit-box; min-height: 2.8rem; overflow: hidden; margin: 0.55rem 0 0; color: var(--color-text-muted); font-size: var(--text-sm); line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.game-card__meta { display: flex; align-items: center; gap: 1rem; margin-top: 0.9rem; color: var(--color-text-muted); font-size: var(--text-xs); }
.game-card__meta span { display: inline-flex; align-items: center; gap: 0.35rem; }

@media (max-width: 639px) {
  .game-card__body { padding: 0.85rem; }
  h3 { font-size: var(--text-sm); min-height: 2.65rem; }
  .game-card__rating { right: 0.5rem; bottom: 0.5rem; padding: 0.3rem 0.5rem; }
}

@media (prefers-reduced-motion: reduce) {
  .game-card:hover { transform: none; }
}
</style>
