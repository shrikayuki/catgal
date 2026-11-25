<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { Send } from 'lucide-vue-next'
import { useAuthStore } from '../../stores/auth'
import { useRoute } from 'vue-router'
import { useNotificationStore } from '../../stores/notifications'
import { addComment, replyComment } from '../../api/community'
import BaseButton from '../ui/BaseButton.vue'
import UserAvatar from '../ui/UserAvatar.vue'
const props = defineProps<{ gameId: number; replyId?: number; rootId?: number; replyName?: string }>()
const emit = defineEmits<{ saved: []; cancel: []; busyChange: [busy: boolean] }>()
const auth = useAuthStore(),
  route = useRoute()
const notifications = useNotificationStore()
const content = ref(''),
  busy = ref(false),
  error = ref('')
const editor = ref<HTMLTextAreaElement | null>(null)
onMounted(async () => {
  if (props.replyId) {
    await nextTick()
    editor.value?.focus()
  }
})
async function submit() {
  if (busy.value || !content.value.trim()) return
  busy.value = true
  emit('busyChange', true)
  error.value = ''
  try {
    if (props.replyId && props.rootId)
      await replyComment(props.gameId, props.rootId, props.replyId, content.value.trim())
    else await addComment(props.gameId, content.value.trim())
    content.value = ''
    emit('saved')
    notifications.show(props.replyId ? '回复已发表' : '评论已发表', 'success')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '评论提交失败'
  } finally {
    busy.value = false
    emit('busyChange', false)
  }
}
</script>
<template>
  <form class="discussion-composer" :class="{ 'discussion-composer--reply': replyId }" @submit.prevent="submit">
    <UserAvatar v-if="!replyId" :src="auth.session?.avatarUrl" />
    <div class="discussion-composer__body">
      <div v-if="auth.isLoggedIn" class="discussion-composer__editor">
        <label>
          <span :class="{ 'sr-only': !replyId }">{{ replyId ? `回复 ${replyName || '这位玩家'}` : '写评论' }}</span>
          <textarea
            ref="editor"
            v-model="content"
            required
            maxlength="500"
            :rows="replyId ? 2 : 3"
            :placeholder="replyId ? '写下你的回复' : '聊聊你对这部作品的感受…'"
            :disabled="busy"
          />
        </label>
        <div class="discussion-composer__actions">
          <div class="discussion-composer__counter">
            <progress :value="content.length" max="500" aria-hidden="true" />
            <small>{{ content.length }}/500</small>
          </div>
          <BaseButton v-if="replyId" variant="ghost" size="small" :disabled="busy" @click="emit('cancel')">取消</BaseButton>
          <BaseButton type="submit" size="small" :loading="busy" :disabled="!content.trim()">
            <Send v-if="!busy" :size="14" aria-hidden="true" />
            {{ replyId ? '发表回复' : '发表评论' }}
          </BaseButton>
        </div>
      </div>
      <div v-else class="discussion-composer__guest">
        <span>登录后分享你的想法</span>
        <BaseButton :to="{ name: 'login', query: { redirect: route.fullPath } }" size="small">登录</BaseButton>
      </div>
      <p v-if="error && auth.isLoggedIn" class="work-error" role="alert">{{ error }}</p>
    </div>
  </form>
</template>

<style scoped>
.discussion-composer { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: var(--discussion-gap, 1rem); }
.discussion-composer--reply { display: block; }
.discussion-composer__body { min-width: 0; }
.discussion-composer__editor { border: 1px solid var(--color-border-strong); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-sm); transition: border-color var(--motion-fast), box-shadow var(--motion-fast); }
.discussion-composer__editor:has(textarea:focus) { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); }
label { display: grid; gap: .35rem; padding: .65rem .8rem 0; color: var(--color-brand-800); font-size: var(--text-sm); font-weight: 700; }
textarea {
  width: 100%; min-width: 0; padding: .25rem .1rem .65rem; resize: vertical;
  border: 0; background: transparent; color: var(--color-text-primary); font: inherit; font-weight: 400;
}
textarea:focus { outline: 0; }
.discussion-composer__actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: .4rem; padding: .55rem .65rem; border-top: 1px solid var(--color-border); border-radius: 0 0 var(--radius-lg) var(--radius-lg); background: var(--color-bg); }
.discussion-composer__counter { display: flex; align-items: center; gap: .45rem; margin-right: auto; color: var(--color-text-muted); font-variant-numeric: tabular-nums; }
.discussion-composer__counter progress { width: 2.5rem; height: .28rem; border: 0; border-radius: var(--radius-pill); background: var(--color-brand-100); color: var(--color-brand-700); }
.discussion-composer__counter progress::-webkit-progress-bar { border-radius: inherit; background: var(--color-brand-100); }
.discussion-composer__counter progress::-webkit-progress-value { border-radius: inherit; background: var(--color-brand-700); }
.discussion-composer__counter progress::-moz-progress-bar { border-radius: inherit; background: var(--color-brand-700); }
.discussion-composer__guest { display: flex; flex-wrap: wrap; gap: .65rem; align-items: center; justify-content: space-between; padding: .75rem .85rem; border-radius: var(--radius-md); background: var(--color-surface-soft); color: var(--color-text-secondary); font-size: var(--text-sm); }
@media (max-width: 439px) { .discussion-composer__counter progress { display: none; } }
</style>
