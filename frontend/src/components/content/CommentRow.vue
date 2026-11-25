<script setup lang="ts">
import { MessageCircle, Trash2 } from 'lucide-vue-next'
import type { GameComment } from '../../types/game'
import { formatDate } from '../../utils/format'
import { userRoute } from '../../utils/navigation'
import type { LikeState } from '../../utils/like-state'
import UserAvatar from '../ui/UserAvatar.vue'
import ContentLikeButton from './ContentLikeButton.vue'

const props = defineProps<{
  comment: GameComment
  root?: GameComment
  disabled?: boolean
  preview?: boolean
}>()
const emit = defineEmits<{ reply: []; remove: []; refresh: []; like: [state: LikeState] }>()

function hasReplyTarget(): boolean {
  const { comment, root } = props
  if (!root) return false
  const repliesToAnotherComment = comment.replyCommentId != null && comment.replyCommentId !== root.id
  const repliesToAnotherUser = comment.replyUserId != null && comment.replyUserId !== root.userId
  return repliesToAnotherComment || repliesToAnotherUser
}

function hasProfile(id: number | null): id is number {
  return id !== null && Number.isSafeInteger(id) && id > 0
}
</script>

<template>
  <div class="discussion-row" :class="{ 'discussion-row--reply': root, 'discussion-row--preview': preview }">
    <UserAvatar :src="comment.avatarUrl" />
    <div class="discussion-row__body">
      <p class="discussion-row__author">
        <RouterLink v-if="hasProfile(comment.userId) && comment.username" :to="userRoute(comment.userId)">{{ comment.username }}</RouterLink>
        <span v-else>{{ comment.username || '社区成员' }}</span>
      </p>
      <p class="discussion-row__text"><template v-if="hasReplyTarget()"><span class="discussion-row__target">回复 <RouterLink v-if="comment.replyUsername && hasProfile(comment.replyUserId)" :to="userRoute(comment.replyUserId)">@{{ comment.replyUsername }}</RouterLink><span v-else>{{ comment.replyUsername || '该评论' }}</span>：</span></template>{{ comment.content }}</p>
      <div v-if="!preview" class="discussion-row__actions">
        <time :datetime="comment.createTime || undefined">{{ formatDate(comment.createTime) }}</time>
        <ContentLikeButton type="comment" :target-id="comment.id" :target-label="`${comment.username || '社区成员'}的${root ? '回复' : '评论'}`" :count="comment.likeCount" :liked="comment.isLike" compact @change="emit('like', $event)" @refresh="emit('refresh')" />
        <button type="button" class="discussion-action" :disabled="disabled" @click="emit('reply')"><MessageCircle :size="15" aria-hidden="true" />回复</button>
        <button v-if="comment.isMyComment" type="button" class="discussion-action discussion-action--remove" :disabled="disabled" @click="emit('remove')"><Trash2 :size="15" aria-hidden="true" />删除</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.discussion-row { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: var(--discussion-gap); }
.discussion-row--reply { --discussion-avatar-size: 1.85rem; --discussion-gap: .65rem; }
.discussion-row__body { min-width: 0; }
.discussion-row__author, .discussion-row__text { margin: 0; overflow-wrap: anywhere; }
.discussion-row__author { color: var(--color-text-secondary); font-size: var(--text-sm); font-weight: 700; }
.discussion-row__author a { color: inherit; text-decoration: none; }
.discussion-row__author a:hover, .discussion-row__target a { color: var(--color-brand-700); }
.discussion-row__text { margin-top: .28rem; color: var(--color-text-primary); font-size: var(--text-sm); line-height: 1.65; white-space: pre-wrap; }
.discussion-row:not(.discussion-row--reply) .discussion-row__text { font-size: var(--text-base); }
.discussion-row__target { color: var(--color-text-secondary); }
.discussion-row__target a { text-decoration: none; }
.discussion-row__target a:hover { text-decoration: underline; }
.discussion-row__actions { display: flex; align-items: center; flex-wrap: wrap; column-gap: .35rem; margin-top: .15rem; }
.discussion-row__actions time { margin-right: .3rem; color: var(--color-text-muted); font-size: var(--text-xs); }
.discussion-action { display: inline-flex; min-height: 2.25rem; align-items: center; gap: .3rem; padding: .25rem .35rem; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--color-text-secondary); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.discussion-action:hover:not(:disabled) { background: var(--color-brand-50); color: var(--color-brand-700); }
.discussion-action--remove:hover:not(:disabled) { color: var(--color-danger); }
.discussion-action:disabled { opacity: .55; cursor: default; }
.discussion-row--preview .discussion-row__text { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
@media (max-width: 480px) {
  .discussion-row__actions { column-gap: .15rem; }
  .discussion-row__actions time { flex-basis: 100%; margin: .2rem 0 0; }
}
</style>
