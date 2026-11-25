<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { BookOpen, Check, Search } from 'lucide-vue-next'
import { getGames, getGame } from '../../api/games'
import type { GameSummary } from '../../types/game'
import BaseButton from '../ui/BaseButton.vue'
const model = defineModel<number | null>({ required: true })
const keyword = ref(''),
  results = ref<GameSummary[]>([]),
  selected = ref(''),
  error = ref(''),
  loading = ref(true)
const selectionError = ref('')
let controller: AbortController | undefined
async function search() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const response = await getGames(
      { keyword: keyword.value.trim(), pageNo: 1, pageSize: 10 },
      request.signal,
    )
    if (!request.signal.aborted) results.value = response.list ?? []
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '搜索游戏失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
function choose(game: GameSummary) {
  model.value = game.id
  selected.value = game.name
  selectionError.value = ''
}
onMounted(async () => {
  const request = new AbortController()
  controller = request
  if (model.value) {
    try {
      const game = await getGame(model.value, request.signal)
      if (request.signal.aborted) return
      selected.value = game.name
    } catch {
      if (request.signal.aborted) return
      model.value = null
      selectionError.value = '指定作品暂时不可用，请重新选择。'
    }
  }
  if (request.signal.aborted) return
  void search()
})
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <fieldset class="game-picker">
    <legend>所属游戏</legend>
    <p v-if="model" class="game-picker__selection" role="status"><Check :size="17" aria-hidden="true" />{{ selected ? `已选择 ${selected}` : '正在读取作品名称…' }}</p>
    <p v-else-if="selectionError" class="work-muted">{{ selectionError }}</p>
    <div class="game-picker__search">
      <input
        v-model="keyword"
        type="search"
        maxlength="100"
        aria-label="搜索所属游戏"
        placeholder="搜索已发布作品"
        @keydown.enter.prevent="search"
      />
      <BaseButton variant="secondary" :disabled="loading" @click="search"><Search :size="16" aria-hidden="true" />搜索游戏</BaseButton>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="game-picker__message" role="status">正在查找作品…</p>
    <p v-else-if="!results.length" class="game-picker__message" role="status">没有找到匹配的作品，换个关键词试试。</p>
    <div v-else class="game-picker__results" aria-label="可选择的作品">
      <button
        v-for="game in results"
        :key="game.id"
        type="button"
        class="game-picker__option"
        :aria-pressed="model === game.id"
        @click="choose(game)"
      >
        <BookOpen :size="17" aria-hidden="true" />
        <span>{{ game.name }}</span>
        <Check v-if="model === game.id" :size="17" aria-hidden="true" />
      </button>
    </div>
  </fieldset>
</template>

<style scoped>
.game-picker { min-width: 0; margin: 0; padding: 0; border: 0; }
.game-picker legend { margin-bottom: .6rem; padding: 0; color: var(--color-text-primary); font-size: var(--text-sm); font-weight: 700; }
.game-picker__selection { display: flex; align-items: flex-start; gap: .5rem; margin: 0 0 .65rem; padding: .55rem .75rem; border-radius: var(--radius-sm); background: var(--color-brand-50); color: var(--color-brand-800); font-size: var(--text-sm); overflow-wrap: anywhere; }
.game-picker__selection svg { flex: none; margin-top: .2rem; }
.game-picker__search { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: .55rem; }
.game-picker__message { margin: .75rem 0 0; color: var(--color-text-muted); font-size: var(--text-sm); }
.game-picker__results { display: grid; max-height: 12rem; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .4rem; margin-top: .65rem; padding: .2rem; overflow: auto; }
.game-picker__option { display: flex; min-width: 0; min-height: 2.75rem; align-items: center; gap: .45rem; padding: .45rem .6rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text-secondary); text-align: start; font-size: var(--text-sm); cursor: pointer; }
.game-picker__option svg { flex: none; color: var(--color-brand-600); }
.game-picker__option span { flex: 1; overflow-wrap: anywhere; }
.game-picker__option:hover, .game-picker__option[aria-pressed='true'] { border-color: var(--color-brand-300); background: var(--color-brand-50); color: var(--color-brand-800); }
@media (max-width: 520px) { .game-picker__results { grid-template-columns: minmax(0, 1fr); } }
</style>
