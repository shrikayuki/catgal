<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { MessageCircle, RefreshCw } from 'lucide-vue-next'
import { getGameComments } from '../../api/games'
import type { PageData } from '../../api/types'
import type { GameComment } from '../../types/game'
import { useAuthStore } from '../../stores/auth'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'
import CommentComposer from './CommentComposer.vue'
import CommentThread from './CommentThread.vue'

const props = defineProps<{ gameId: number; currentPage: number }>()
const emit = defineEmits<{ pageChange: [page: number]; countChange: [count: number] }>()
const auth = useAuthStore()
const comments = ref<PageData<GameComment> | null>(null)
const loading = ref(false)
const error = ref('')
const composerVersion = ref(0)
let controller: AbortController | undefined

async function load(): Promise<void> {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getGameComments(props.gameId, props.currentPage, 10, request.signal)
    if (request.signal.aborted) return
    const lastPage = Math.max(1, result.page?.pages ?? 0)
    if (props.currentPage > lastPage) {
      emit('pageChange', lastPage)
      return
    }
    comments.value = result.page
    if (result.commentCount != null) emit('countChange', result.commentCount)
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '讨论暂时无法加载'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

function posted(): void {
  if (props.currentPage === 1) void load()
  else emit('pageChange', 1)
}

// 翻页或切换账号时清理旧列表；同页刷新保留各楼展开位置及正在编辑的回复。
watch(() => [props.gameId, props.currentPage, auth.session?.token] as const, (next, previous) => {
  if (!previous || next[0] !== previous[0] || next[2] !== previous[2]) composerVersion.value++
  comments.value = null
  void load()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="game-discussion" aria-label="作品讨论">
    <CommentComposer :key="composerVersion" :game-id="gameId" @saved="posted" />
    <div class="game-discussion__list" :aria-busy="loading">
      <p v-if="loading && !comments" class="discussion-status" role="status">正在加载讨论…</p>
      <div v-if="error" class="discussion-status" role="alert">
        {{ error }}
        <BaseButton size="small" variant="secondary" @click="load"><RefreshCw :size="15" aria-hidden="true" />重新加载</BaseButton>
      </div>
      <div v-if="!loading && !error && !comments?.list.length" class="discussion-status discussion-status--empty">
        <MessageCircle :size="27" aria-hidden="true" />还没有评论，来分享你的感受吧。
      </div>
      <CommentThread v-for="root in comments?.list ?? []" :key="root.id" :game-id="gameId" :root="root" @changed="load" @removed="load" />
      <PaginationControl v-if="comments && !loading" :current-page="currentPage" :total-pages="comments.pages" @change="emit('pageChange', $event)" />
    </div>
  </section>
</template>

<style scoped>
.game-discussion {
  --discussion-avatar-size: 2.75rem;
  --discussion-gap: .9rem;
  max-width: 54rem;
}
.game-discussion__list { margin-top: 1rem; }
.discussion-status { display: flex; flex-wrap: wrap; align-items: center; gap: .65rem; padding: 1rem 0; color: var(--color-text-secondary); font-size: var(--text-sm); }
.discussion-status--empty { justify-content: center; min-height: 9rem; color: var(--color-text-muted); }
@media (max-width: 480px) {
  .game-discussion { --discussion-avatar-size: 2.2rem; --discussion-gap: .65rem; }
}
</style>
