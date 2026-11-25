<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId } from 'vue'
import { ChevronDown, ChevronUp } from 'lucide-vue-next'
import { deleteComment, getThreadReplies } from '../../api/community'
import type { GameComment } from '../../types/game'
import type { PageData } from '../../api/types'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'
import type { LikeState } from '../../utils/like-state'
import CommentComposer from './CommentComposer.vue'
import CommentRow from './CommentRow.vue'

const props = defineProps<{ gameId: number; root: GameComment }>()
const emit = defineEmits<{ changed: []; removed: [] }>()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const notifications = useNotificationStore()
const repliesId = useId()
const expanded = ref(false)
const replies = ref<PageData<GameComment> | null>(null)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const retryPage = ref(1)
const replyTarget = ref<GameComment | null>(null)
const sending = ref(false)
const deleting = ref(false)
const writing = computed(() => sending.value || deleting.value)
const likeOverrides = ref(new Map<number, LikeState>())
const preview = computed(() => props.root.replyPreview ?? [])
const count = computed(() => replies.value?.total ?? props.root.replyCount ?? 0)
let controller: AbortController | undefined
let active = true

function displayed(comment: GameComment): GameComment {
  const state = likeOverrides.value.get(comment.id)
  return state ? { ...comment, ...state } : comment
}

async function load(pageNo = 1): Promise<void> {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  retryPage.value = pageNo
  try {
    let result = await getThreadReplies(props.root.id, pageNo, request.signal)
    if (request.signal.aborted) return
    // 删除最后一页的最后一条后回到可用页，且不重复加载已经显示的楼层。
    if (pageNo > 1 && pageNo > result.pages) {
      pageNo = Math.max(1, result.pages)
      result = await getThreadReplies(props.root.id, pageNo, request.signal)
      if (request.signal.aborted) return
    }
    result.list.forEach(reply => likeOverrides.value.delete(reply.id))
    replies.value = result
    page.value = pageNo
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '回复暂时无法加载'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

function toggleReplies(): void {
  if (writing.value) return
  if (expanded.value) {
    controller?.abort()
    if (replyTarget.value?.id !== props.root.id) replyTarget.value = null
    expanded.value = false
  } else {
    expanded.value = true
    replies.value = null
    void load()
  }
}

function replyTo(comment: GameComment): void {
  if (!auth.isLoggedIn) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  replyTarget.value = comment
}

function refreshLike(comment: GameComment): void {
  likeOverrides.value.delete(comment.id)
  if (comment.id === props.root.id) emit('changed')
  else void load(page.value)
}

function saved(): void {
  replyTarget.value = null
  expanded.value = true
  void load()
  emit('changed')
}

async function remove(comment: GameComment): Promise<void> {
  const isRoot = comment.id === props.root.id
  if (writing.value || !window.confirm(isRoot ? '确认删除这条评论及其所有回复？' : '确认删除这条回复？')) return
  deleting.value = true
  try {
    await deleteComment(comment.id)
    if (!active) return
    if (replyTarget.value?.id === comment.id) replyTarget.value = null
    notifications.show(isRoot ? '评论已删除' : '回复已删除', 'success')
    if (isRoot) emit('removed')
    else {
      await load(page.value)
      if (active) emit('changed')
    }
  } catch (cause) {
    if (active) notifications.show(cause instanceof Error ? cause.message : '暂时无法删除，请稍后再试', 'error')
  } finally { deleting.value = false }
}

function showPage(pageNo: number): void {
  if (replyTarget.value?.id !== props.root.id) replyTarget.value = null
  void load(pageNo)
}

onBeforeUnmount(() => { active = false; controller?.abort() })
</script>

<template>
  <article class="discussion-thread">
    <CommentRow :comment="displayed(root)" :disabled="writing" @reply="replyTo(root)" @remove="remove(root)" @like="likeOverrides.set(root.id, $event)" @refresh="refreshLike(root)" />
    <div v-if="replyTarget?.id === root.id || count || expanded" class="discussion-thread__below">
      <CommentComposer v-if="replyTarget?.id === root.id" :game-id="gameId" :root-id="root.id" :reply-id="root.id" :reply-name="root.username" @saved="saved" @cancel="replyTarget = null" @busy-change="sending = $event" />
      <div v-if="count || expanded" class="discussion-replies">
        <div v-if="!expanded" class="discussion-replies__preview">
          <CommentRow v-for="reply in preview" :key="reply.id" :comment="reply" :root="root" preview />
        </div>
        <button type="button" class="discussion-replies__toggle" :aria-expanded="expanded" :aria-controls="repliesId" :disabled="writing" @click="toggleReplies">
          <template v-if="expanded">收起回复 <ChevronUp :size="16" aria-hidden="true" /></template>
          <template v-else>查看全部 {{ count }} 条回复 <ChevronDown :size="16" aria-hidden="true" /></template>
        </button>
        <div v-show="expanded" :id="repliesId" class="discussion-replies__expanded" :aria-busy="loading">
          <template v-if="expanded">
            <p v-if="error" class="discussion-replies__status" role="alert">{{ error }} <button type="button" class="discussion-replies__toggle" @click="load(retryPage)">重试</button></p>
            <p v-if="loading" class="discussion-replies__status" role="status">正在加载回复…</p>
            <p v-else-if="!error && replies?.list.length === 0" class="discussion-replies__status">暂无回复</p>
            <div v-for="reply in replies?.list ?? []" :key="reply.id" class="discussion-replies__item">
              <CommentRow :comment="displayed(reply)" :root="root" :disabled="writing || loading" @reply="replyTo(reply)" @remove="remove(reply)" @like="likeOverrides.set(reply.id, $event)" @refresh="refreshLike(reply)" />
              <CommentComposer v-if="replyTarget?.id === reply.id" :game-id="gameId" :root-id="root.id" :reply-id="reply.id" :reply-name="reply.username" @saved="saved" @cancel="replyTarget = null" @busy-change="sending = $event" />
            </div>
            <nav v-if="replies && replies.pages > 1" class="discussion-replies__pages" aria-label="回复分页">
              <button type="button" :disabled="loading || writing || page <= 1" @click="showPage(page - 1)">上一页</button>
              <span>{{ page }} / {{ replies.pages }}</span>
              <button type="button" :disabled="loading || writing || page >= replies.pages" @click="showPage(page + 1)">下一页</button>
            </nav>
          </template>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.discussion-thread { padding-block: 1.2rem; border-bottom: 1px solid var(--color-border); }
.discussion-thread__below { display: grid; gap: .7rem; margin: .4rem 0 0 calc(var(--discussion-avatar-size) + var(--discussion-gap)); min-width: 0; }
.discussion-replies { padding: .65rem .85rem; border-radius: var(--radius-md); background: var(--color-surface-soft); }
.discussion-replies__preview { display: grid; gap: .65rem; padding-bottom: .2rem; }
.discussion-replies__toggle, .discussion-replies__pages button { display: inline-flex; min-height: 2.1rem; align-items: center; gap: .3rem; padding: .2rem .3rem; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--color-brand-800); font-size: var(--text-xs); font-weight: 700; cursor: pointer; }
.discussion-replies__toggle:hover:not(:disabled), .discussion-replies__pages button:hover:not(:disabled) { text-decoration: underline; }
.discussion-replies__toggle:disabled, .discussion-replies__pages button:disabled { color: var(--color-text-muted); opacity: .65; cursor: default; }
.discussion-replies__status { margin: .35rem 0; color: var(--color-text-secondary); font-size: var(--text-sm); }
.discussion-replies__item { border-top: 1px solid var(--color-border); padding-block: .75rem; }
.discussion-replies__item :deep(.discussion-composer) { margin: .45rem 0 0 2.5rem; }
.discussion-replies__pages { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: .35rem; color: var(--color-text-muted); font-size: var(--text-xs); }
@media (max-width: 480px) {
  .discussion-replies { padding-inline: .65rem; }
  .discussion-replies__item :deep(.discussion-composer) { margin-inline-start: 0; }
}
</style>
