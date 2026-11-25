<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { LoaderCircle, ThumbsUp } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { likeContent, unlikeContent, type LikeTargetType } from '../../api/likes'
import { ApiError } from '../../api/types'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'
import { formatCompactNumber } from '../../utils/format'
import { resolveLikeState, type LikeState } from '../../utils/like-state'

const props = defineProps<{
  type: LikeTargetType
  targetId: number
  targetLabel: string
  liked: boolean | null
  count: number | null
  compact?: boolean
}>()
const emit = defineEmits<{ change: [state: LikeState]; refresh: [] }>()
const auth = useAuthStore()
const notifications = useNotificationStore()
const route = useRoute()
const router = useRouter()
const pending = ref(false)
const cheering = ref(false)
const isLiked = computed(() => auth.isLoggedIn && props.liked === true)
const actionLabel = computed(() => `${isLiked.value ? '取消点赞' : '点赞'}「${props.targetLabel}」`)
let controller: AbortController | undefined
let cheerTimer: number | undefined

function clearCheer() {
  window.clearTimeout(cheerTimer)
  cheerTimer = undefined
  cheering.value = false
}

async function toggle() {
  if (pending.value) return
  if (!auth.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const token = auth.session?.token
  const nextLiked = !isLiked.value
  clearCheer()
  const request = new AbortController()
  controller = request
  pending.value = true
  try {
    const changed = nextLiked
      ? await likeContent(props.type, props.targetId, request.signal)
      : await unlikeContent(props.type, props.targetId, request.signal)
    if (request.signal.aborted || token !== auth.session?.token) return
    emit('change', resolveLikeState(props.count, nextLiked, changed))
    if (changed && nextLiked) {
      cheering.value = true
      // 动效被系统设置隐藏时也会清理，避免依赖 animationend。
      cheerTimer = window.setTimeout(clearCheer, 520)
    }
    if (!changed) {
      notifications.show('已同步最新点赞状态', 'info')
      emit('refresh')
    }
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401 && !auth.isLoggedIn) {
      notifications.show('登录已失效，请重新登录后点赞', 'info')
      return
    }
    if (request.signal.aborted || token !== auth.session?.token) return
    notifications.show(cause instanceof Error ? cause.message : '点赞请求未确认，请稍后重试', 'error')
    // A failed response may follow a successful write. Read the authoritative state before another action.
    emit('refresh')
  } finally {
    if (!request.signal.aborted) pending.value = false
  }
}

function cancelPending() { controller?.abort(); pending.value = false; clearCheer() }
watch(() => [props.type, props.targetId, auth.session?.token], cancelPending)
onBeforeUnmount(cancelPending)
</script>

<template>
  <button type="button" class="content-like" :class="{ 'content-like--active': isLiked, 'content-like--compact': compact, 'content-like--cheer': cheering }"
    :aria-label="actionLabel" :aria-pressed="isLiked" :aria-busy="pending" :disabled="pending"
    :title="auth.isLoggedIn ? actionLabel : '登录后可点赞'" @click="toggle">
    <LoaderCircle v-if="pending" class="content-like__spinner" :size="16" aria-hidden="true" />
    <ThumbsUp v-else :size="16" :fill="isLiked ? 'currentColor' : 'none'" aria-hidden="true" />
    <span v-if="!compact">{{ isLiked ? '已赞' : '点赞' }}</span><span class="content-like__count">{{ formatCompactNumber(count) }}</span>
    <span v-if="cheering" class="content-like__sparkles" aria-hidden="true"><i /><i /><i /></span>
  </button>
</template>

<style scoped>
.content-like { position: relative; display: inline-flex; flex: 0 0 auto; min-height: 2.75rem; align-items: center; justify-content: center; gap: .4rem; padding: .55rem .8rem; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--color-surface); color: var(--color-text-secondary); font-size: var(--text-xs); font-weight: 700; cursor: pointer; transition: border-color var(--motion-fast), background var(--motion-fast); }
.content-like:hover:not(:disabled) { border-color: var(--color-brand-400); background: var(--color-brand-50); color: var(--color-brand-700); }
.content-like--active { border-color: var(--color-brand-300); background: var(--color-brand-50); color: var(--color-brand-700); }
.content-like--compact { min-height: 2.25rem; padding: .3rem .55rem; border-color: transparent; background: transparent; }
.content-like--compact.content-like--active { color: var(--color-brand-700); }
.content-like:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 3px; }
.content-like:disabled { cursor: wait; opacity: .65; }
.content-like__count { min-width: 1ch; font-variant-numeric: tabular-nums; }
.content-like__spinner { animation: like-spin 1s linear infinite; }
.content-like__sparkles { display: none; }
@keyframes like-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: no-preference) {
  .content-like--cheer > svg { animation: like-pop 360ms cubic-bezier(.2, .75, .3, 1); }
  .content-like__sparkles { position: absolute; top: 45%; left: 1.3rem; display: block; pointer-events: none; animation: like-fade 500ms ease-out both; }
  .content-like__sparkles i { --sparkle-x: -.7rem; --sparkle-y: -.95rem; position: absolute; width: .3rem; height: .3rem; border-radius: 20%; background: var(--color-brand-500); animation: like-sparkle 500ms ease-out both; }
  .content-like__sparkles i:nth-child(2) { --sparkle-x: .6rem; --sparkle-y: -1.15rem; background: var(--color-accent-pink); }
  .content-like__sparkles i:nth-child(3) { --sparkle-x: 1.04rem; --sparkle-y: -.4rem; background: var(--color-warning); }
  @keyframes like-pop { 45% { transform: scale(1.28) rotate(-11deg); } }
  @keyframes like-fade { from { opacity: 1; } to { opacity: 0; } }
  @keyframes like-sparkle { to { transform: translate(var(--sparkle-x), var(--sparkle-y)) rotate(45deg) scale(.65); } }
}
@media (prefers-reduced-motion: reduce) { .content-like__spinner { animation: none; } }
</style>
