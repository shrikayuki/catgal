<script setup lang="ts">
import { readPublicId, userRoute } from '../utils/navigation'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getConnections, type ConnectionUser } from '../api/discovery'
import { followUser, unfollowUser } from '../api/users'
import type { PageData } from '../api/types'
import { useAuthStore } from '../stores/auth'
import BaseButton from '../components/ui/BaseButton.vue'
import UserAvatar from '../components/ui/UserAvatar.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
const route = useRoute(),
  auth = useAuthStore()
const userId = computed(() => readPublicId(route.params.userId)),
  kind = computed(() => (route.query.tab === 'fans' ? ('fans' as const) : ('followings' as const)))
const data = ref<PageData<ConnectionUser> | null>(null),
  page = ref(1),
  error = ref(''),
  busy = ref(false),
  loading = ref(false)
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  data.value = null
  error.value = ''
  loading.value = true
  try {
    const result = await getConnections(userId.value, kind.value, pageNo, request.signal)
    if (!request.signal.aborted) {
      data.value = result
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '列表读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function toggle(user: ConnectionUser) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (user.isFollow) await unfollowUser(user.userId)
    else await followUser(user.userId)
    await load(page.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '关注操作失败'
  } finally {
    busy.value = false
  }
}
watch([() => route.fullPath, () => auth.session?.token], () => void load(), { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <div class="container section workspace-page">
    <header>
      <h1>{{ kind === 'fans' ? '粉丝' : '关注' }}</h1>
      <nav class="work-tabs">
        <RouterLink :to="{ query: { tab: 'followings' } }">关注列表</RouterLink>
        <RouterLink :to="{ query: { tab: 'fans' } }">粉丝列表</RouterLink>
        <RouterLink :to="userRoute(userId)">返回主页</RouterLink>
      </nav>
    </header>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading">正在加载…</p>
    <div class="work-grid">
      <article v-for="user in data?.list ?? []" :key="user.userId" class="work-card">
        <div class="connection-user"><UserAvatar :src="user.avatarUrl" /><h2>{{ user.username }}</h2></div>
        <p class="work-muted">{{ user.signature || '这个人还没有留下签名。' }}</p>
        <div class="work-toolbar">
          <BaseButton :to="userRoute(user.userId)" size="small">查看主页</BaseButton>
          <BaseButton
            v-if="
              auth.isLoggedIn &&
              auth.session?.userId !== user.userId &&
              (kind === 'fans' || auth.session?.userId === userId)
            "
            variant="secondary"
            size="small"
            :disabled="busy"
            @click="toggle(user)"
          >
            {{ user.isFollow ? '取消关注' : '关注' }}
          </BaseButton>
        </div>
      </article>
    </div>
    <p v-if="!loading && !data?.list.length" class="work-empty">暂无记录。</p>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
  </div>
</template>

<style scoped>
.connection-user { display: flex; align-items: center; gap: .65rem; }
.connection-user h2 { min-width: 0; overflow-wrap: anywhere; }
</style>
