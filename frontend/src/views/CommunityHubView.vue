<script setup lang="ts">
import { gameRoute } from '../utils/navigation'
import { onBeforeUnmount, ref } from 'vue'
import { getGames } from '../api/games'
import type { GameSummary } from '../types/game'
import BaseButton from '../components/ui/BaseButton.vue'
const games = ref<GameSummary[]>([]),
  error = ref('')
const controller = new AbortController()
getGames({ pageNo: 1, pageSize: 20 }, controller.signal)
  .then((result) => (games.value = result.list))
  .catch((cause) => {
    if (!controller.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '社区作品加载失败'
  })
onBeforeUnmount(() => controller.abort())
</script>
<template>
  <div class="container section workspace-page">
    <header>
      <h1>作品社区</h1>
      <p class="work-muted">选择一部作品，进入讨论、评价和资源交流。</p>
    </header>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <div class="work-grid">
      <article v-for="game in games" :key="game.id" class="work-card">
        <h2>{{ game.name }}</h2>
        <p class="work-muted">
          {{ game.commentCount ?? 0 }} 条讨论 · {{ game.reviewCount ?? 0 }} 条评价
        </p>
        <div class="work-toolbar">
          <BaseButton :to="gameRoute(game.id, { tab: 'comments' }, '#discussion')" size="small">
            参与讨论
          </BaseButton>
          <BaseButton
            :to="gameRoute(game.id, { tab: 'reviews' }, '#discussion')"
            variant="secondary"
            size="small"
          >
            查看评价
          </BaseButton>
        </div>
      </article>
    </div>
    <BaseButton to="/games" variant="ghost">浏览全部作品</BaseButton>
  </div>
</template>
