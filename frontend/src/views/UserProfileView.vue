<script setup lang="ts">
import { connectionsRoute, folderRoute, gameRoute, readPublicId, resourceEditRoute } from '../utils/navigation'
import {
  BookHeart,
  CalendarDays,
  Edit3,
  FileArchive,
  FolderHeart,
  HeartHandshake,
  MessageCircle,
  RefreshCw,
  Sparkles,
  Star,
  ThumbsUp,
  UserPlus,
  UserRound,
  UsersRound,
} from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { followUser, getUserHome, unfollowUser } from '../api/users'
import { deleteResource } from '../api/resources'
import type { PageData } from '../api/types'
import BaseButton from '../components/ui/BaseButton.vue'
import UserAvatar from '../components/ui/UserAvatar.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import ResourceHistoryDialog from '../components/content/ResourceHistoryDialog.vue'
import ResourceSubmitDialog from '../components/content/ResourceSubmitDialog.vue'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import type {
  UserComment,
  UserFavoriteFolder,
  UserHome,
  UserHomeItem,
  UserHomeTab,
  UserResource,
  UserReview,
} from '../types/user'
import { userHomeTabCodes } from '../types/user'
import { formatCompactNumber, formatDate, getPlatformLabel, getPlayStatusLabel, getRecommendLabel } from '../utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
const profile = ref<UserHome | null>(null)
const loading = ref(true)
const error = ref('')
const followLoading = ref(false)
const historyResource = ref<UserResource | null>(null)
const resubmitResource = ref<UserResource | null>(null)
let controller: AbortController | null = null

const validTabs: UserHomeTab[] = ['comments', 'reviews', 'favorites', 'resources']
const activeTab = computed<UserHomeTab>(() => {
  const tab = typeof route.query.tab === 'string' ? route.query.tab : 'comments'
  return validTabs.includes(tab as UserHomeTab) ? (tab as UserHomeTab) : 'comments'
})
const currentPage = computed(() => {
  const value = Number(typeof route.query.pageNo === 'string' ? route.query.pageNo : '1')
  return Number.isInteger(value) && value > 0 ? value : 1
})
const routeUserId = computed(() => {
  if (route.name === 'profile') return undefined
  const value = readPublicId(route.params.userId)
  return Number.isInteger(value) && value > 0 ? value : Number.NaN
})
const isOwnProfile = computed(() => Boolean(auth.session && profile.value?.userId === auth.session.userId))
const canManageResources = computed(() => isOwnProfile.value && (auth.session?.role === 1 || auth.isAdmin))
const page = computed<PageData<UserHomeItem> | null>(() => profile.value?.pageData ?? null)
const roleLabel = computed(() => {
  if (profile.value?.role === 2) return '管理员'
  if (profile.value?.role === 1) return '资源创作者'
  return '社区成员'
})

const tabs: Array<{ key: UserHomeTab; label: string; icon: typeof MessageCircle }> = [
  { key: 'comments', label: '评论', icon: MessageCircle },
  { key: 'reviews', label: '评价', icon: Star },
  { key: 'favorites', label: '收藏夹', icon: FolderHeart },
  { key: 'resources', label: '发布资源', icon: FileArchive },
]

function tabCount(tab: UserHomeTab): number {
  if (!profile.value) return 0
  if (tab === 'reviews') return profile.value.reviewCount ?? 0
  if (tab === 'favorites') return profile.value.folderCount ?? 0
  if (tab === 'resources') return profile.value.resourceCount ?? 0
  return profile.value.commentCount ?? 0
}

function isComment(item: UserHomeItem): item is UserComment {
  return 'content' in item
}

function isReview(item: UserHomeItem): item is UserReview {
  return 'score' in item
}

function isFolder(item: UserHomeItem): item is UserFavoriteFolder {
  return 'isPublic' in item
}

function isResource(item: UserHomeItem): item is UserResource {
  return 'coverUrl' in item
}

async function loadProfile(): Promise<void> {
  if (Number.isNaN(routeUserId.value)) {
    error.value = '这个主页链接暂时无法打开'
    loading.value = false
    return
  }
  controller?.abort()
  controller = new AbortController()
  const requestController = controller
  loading.value = true
  error.value = ''
  try {
    const result = await getUserHome<UserHomeItem>(
      {
        userId: routeUserId.value,
        tab: userHomeTabCodes[activeTab.value],
        pageNo: currentPage.value,
        pageSize: 10,
      },
      requestController.signal,
    )
    if (requestController.signal.aborted) return
    profile.value = result
    document.title = `${profile.value.username} · Catgal`
  } catch (caught) {
    if (caught instanceof DOMException && caught.name === 'AbortError') return
    error.value = caught instanceof Error ? caught.message : '个人主页加载失败'
  } finally {
    if (controller === requestController) loading.value = false
  }
}

function selectTab(tab: UserHomeTab): void {
  void router.push({ query: { tab: tab === 'comments' ? undefined : tab } })
}

function changePage(pageNo: number): void {
  void router.push({ query: { ...route.query, pageNo: pageNo > 1 ? String(pageNo) : undefined } })
}

function resourceSubmitted(): void {
  resubmitResource.value = null
  void loadProfile()
}

async function toggleFollow(): Promise<void> {
  if (!profile.value) return
  if (!auth.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  followLoading.value = true
  try {
    if (profile.value.isFollowing) {
      await unfollowUser(profile.value.userId)
      profile.value.isFollowing = false
      notifications.show('已取消关注', 'info')
    } else {
      await followUser(profile.value.userId)
      profile.value.isFollowing = true
      notifications.show(`已关注 ${profile.value.username}`, 'success')
    }
    await loadProfile()
  } catch (caught) {
    notifications.show(caught instanceof Error ? caught.message : '操作失败', 'error')
  } finally {
    followLoading.value = false
  }
}

async function removeResource(id: number) {
  if (followLoading.value || !window.confirm('确认删除自己发布的这条资源？')) return
  followLoading.value = true
  try {
    await deleteResource(id)
    notifications.show('资源已删除', 'success')
    await loadProfile()
  } catch (cause) {
    notifications.show(cause instanceof Error ? cause.message : '删除资源失败', 'error')
  } finally {
    followLoading.value = false
  }
}

watch([routeUserId, activeTab, currentPage, () => auth.session?.token], () => {
  historyResource.value = null
  resubmitResource.value = null
  void loadProfile()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="profile-page">
    <section v-if="loading" class="container profile-state section">
      <StatePanel tone="loading" title="正在整理个人档案" description="评论、评价、收藏和资源正在加载。" />
    </section>
    <section v-else-if="error || !profile" class="container profile-state section">
      <StatePanel tone="error" title="无法打开个人档案" :description="error">
        <BaseButton variant="secondary" @click="loadProfile"><RefreshCw :size="17" />重新加载</BaseButton>
      </StatePanel>
    </section>

    <template v-else>
      <section class="profile-hero">
        <div class="profile-hero__orb profile-hero__orb--one" aria-hidden="true" />
        <div class="profile-hero__orb profile-hero__orb--two" aria-hidden="true" />
        <div class="container profile-hero__inner">
          <div class="profile-avatar">
            <UserAvatar :src="profile.avatarUrl" />
            <span class="profile-avatar__badge"><Sparkles :size="13" aria-hidden="true" />{{ roleLabel }}</span>
          </div>

          <div class="profile-identity">
            <h1>{{ profile.username }}</h1>
            <blockquote>{{ profile.signature || '这位成员还没有写下个人签名。' }}</blockquote>
            <div class="profile-meta">
              <span><CalendarDays :size="16" aria-hidden="true" />{{ formatDate(profile.createTime) }} 加入</span>
              <span v-if="isOwnProfile"><Sparkles :size="16" aria-hidden="true" />{{ profile.cutePoints ?? 0 }} 萌萌点</span>
            </div>
          </div>

          <div class="profile-actions">
            <BaseButton v-if="isOwnProfile" to="/settings" variant="secondary"><Edit3 :size="17" />编辑档案</BaseButton>
            <BaseButton v-else :loading="followLoading" @click="toggleFollow">
              <HeartHandshake v-if="profile.isFollowing" :size="18" />
              <UserPlus v-else :size="18" />
              {{ profile.isFollowing ? '已关注' : '关注' }}
            </BaseButton>
          </div>
        </div>
      </section>

      <section class="profile-metrics">
        <div class="container profile-metrics__grid">
          <div><UsersRound aria-hidden="true" /><strong>{{ formatCompactNumber(profile.followingCount) }}</strong><RouterLink :to="connectionsRoute(profile.userId, 'followings')">关注</RouterLink></div>
          <div><UserRound aria-hidden="true" /><strong>{{ formatCompactNumber(profile.followerCount) }}</strong><RouterLink :to="connectionsRoute(profile.userId, 'fans')">粉丝</RouterLink></div>
          <div><MessageCircle aria-hidden="true" /><strong>{{ formatCompactNumber(profile.commentCount) }}</strong><span>评论</span></div>
          <div><Star aria-hidden="true" /><strong>{{ formatCompactNumber(profile.reviewCount) }}</strong><span>评价</span></div>
          <div><BookHeart aria-hidden="true" /><strong>{{ formatCompactNumber(profile.folderCount) }}</strong><span>收藏夹</span></div>
          <div><FileArchive aria-hidden="true" /><strong>{{ formatCompactNumber(profile.resourceCount) }}</strong><span>资源</span></div>
        </div>
      </section>

      <section class="profile-content section">
        <div class="container">
          <div class="profile-tabs" role="tablist" aria-label="个人主页内容">
            <button v-for="tab in tabs" :key="tab.key" type="button" role="tab" :aria-selected="activeTab === tab.key" :class="{ active: activeTab === tab.key }" @click="selectTab(tab.key)">
              <component :is="tab.icon" :size="17" aria-hidden="true" />{{ tab.label }}<span>{{ tabCount(tab.key) }}</span>
            </button>
          </div>

          <StatePanel v-if="!page || page.list.length === 0" title="这里暂时还没有内容" description="收藏、分享或写下一段感想，慢慢留下你的足迹。" />
          <div v-else class="profile-list">
            <article v-for="item in page.list" :key="`${activeTab}-${item.id}`" class="profile-card">
              <template v-if="isComment(item)">
                <div class="profile-card__icon"><MessageCircle :size="20" /></div>
                <div class="profile-card__body">
                  <RouterLink :to="gameRoute(item.gameId, { tab: 'comments' })">{{ item.gameName || '查看作品' }}</RouterLink>
                  <p>{{ item.content }}</p>
                  <small>{{ formatDate(item.createTime) }} · <ThumbsUp :size="13" />{{ item.likeCount ?? 0 }}</small>
                </div>
              </template>

              <template v-else-if="isReview(item)">
                <div class="profile-card__icon profile-card__icon--score">{{ item.score ?? '—' }}</div>
                <div class="profile-card__body">
                  <RouterLink :to="gameRoute(item.gameId, { tab: 'reviews' })">{{ item.gameName || '查看作品' }}</RouterLink>
                  <div class="chip-row"><span>{{ getRecommendLabel(item.recommendLevel) }}</span><span>{{ getPlayStatusLabel(item.playStatus) }}</span></div>
                  <p>{{ item.briefReview || '这条评价没有附带简评。' }}</p>
                  <small>{{ formatDate(item.createTime) }} · <ThumbsUp :size="13" />{{ item.likeCount ?? 0 }}</small>
                </div>
              </template>

              <template v-else-if="isFolder(item)">
                <div class="profile-card__icon"><FolderHeart :size="21" /></div>
                <div class="profile-card__body">
                  <strong>{{ item.name }}</strong>
                  <p>{{ item.description || '这个收藏夹还没有简介。' }}</p>
                  <small>{{ item.isPublic ? '公开收藏夹' : '仅自己可见' }} · {{ item.gameCount ?? 0 }} 部作品 · {{ formatDate(item.createTime) }}</small>
                  <BaseButton :to="folderRoute(item.id, profile.userId)" variant="ghost" size="small">打开收藏夹</BaseButton>
                </div>
              </template>

              <template v-else-if="isResource(item)">
                <div class="resource-cover">
                  <img v-if="item.coverUrl" :src="item.coverUrl" :alt="`${item.gameName}封面`" loading="lazy" />
                  <FileArchive v-else :size="22" />
                </div>
                <div class="profile-card__body">
                  <RouterLink :to="gameRoute(item.gameId, { tab: 'resources' })">{{ item.gameName || '查看作品' }}</RouterLink>
                  <p>{{ item.name || '分享的资源' }} <span v-if="isOwnProfile && item.status != null" class="work-badge">{{ ['失效','有效','待审核'][item.status] || '未知状态' }}</span></p>
                  <div class="chip-row">
                    <span v-for="type in item.types ?? []" :key="`type-${type}`">{{ type }}</span>
                    <span v-for="platform in item.platforms ?? []" :key="`platform-${platform}`">{{ getPlatformLabel(platform) }}</span>
                  </div>
                  <small>{{ formatDate(item.createTime) }}</small>
                  <p v-if="isOwnProfile && item.reviewReason" class="resource-review-reason work-text">审核说明：{{ item.reviewReason }}</p>
                  <div v-if="isOwnProfile" class="work-toolbar resource-actions">
                    <BaseButton v-if="canManageResources" :to="resourceEditRoute(item.id)" variant="secondary" size="small">编辑资源</BaseButton>
                    <BaseButton v-if="canManageResources && item.status === 0" variant="secondary" size="small" :disabled="followLoading" @click="resubmitResource = item">重新送审</BaseButton>
                    <BaseButton variant="ghost" size="small" @click="historyResource = item">编辑与审核记录</BaseButton>
                    <button v-if="canManageResources" class="work-danger" :disabled="followLoading" @click="removeResource(item.id)">删除自己的资源</button>
                  </div>
                </div>
              </template>
            </article>
          </div>

          <PaginationControl v-if="page" :current-page="currentPage" :total-pages="page.pages" @change="changePage" />
        </div>
      </section>
    </template>
    <ResourceHistoryDialog v-if="historyResource" :resource-id="historyResource.id" :resource-name="historyResource.name || '分享的资源'" @close="historyResource = null" />
    <ResourceSubmitDialog v-if="resubmitResource" :resource-id="resubmitResource.id" @close="resubmitResource = null" @submitted="resourceSubmitted" />
  </div>
</template>

<style scoped>
.resource-actions { margin-top: 0.85rem; }
.resource-review-reason { border-radius: var(--radius-sm); background: var(--color-surface-soft); padding: 0.8rem; font-size: var(--text-sm); }
.profile-state { min-height: 68vh; }
.profile-hero { position: relative; overflow: hidden; background: linear-gradient(120deg, #eaf4f9, #f8fafd 58%, #f8eef5); padding: clamp(3.5rem, 8vw, 6.5rem) 0; }
.profile-hero__orb { position: absolute; border: 1px solid rgb(101 168 209 / 14%); border-radius: 46% 54% 34% 66%; background: rgb(255 255 255 / 28%); }
.profile-hero__orb--one { width: 26rem; height: 26rem; top: -13rem; right: 7%; transform: rotate(24deg); }
.profile-hero__orb--two { width: 13rem; height: 13rem; bottom: -8rem; left: 32%; transform: rotate(-18deg); }
.profile-hero__inner { position: relative; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: clamp(1.5rem, 4vw, 3.5rem); }
.profile-avatar { position: relative; display: grid; width: clamp(8rem, 15vw, 11rem); height: clamp(8rem, 15vw, 11rem); place-items: center; border: 0.45rem solid rgb(255 255 255 / 82%); border-radius: 38% 62% 52% 48%; background: var(--color-brand-100); box-shadow: var(--shadow-lg); color: var(--color-brand-600); transform: rotate(-2deg); }
.profile-avatar { --user-avatar-size: 100%; --user-avatar-radius: 38% 62% 52% 48%; }
.profile-avatar__badge { position: absolute; right: -0.8rem; bottom: 0.6rem; display: inline-flex; align-items: center; gap: 0.3rem; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--color-surface); color: var(--color-accent-violet); font-size: var(--text-xs); font-weight: 800; padding: 0.5rem 0.7rem; white-space: nowrap; }
.profile-identity h1 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: clamp(2.4rem, 5vw, 4.5rem); font-weight: 500; letter-spacing: -0.05em; }
.profile-identity blockquote { max-width: 42rem; margin: 1rem 0 0; color: var(--color-text-secondary); font-size: var(--text-lg); line-height: 1.8; }
.profile-meta { display: flex; flex-wrap: wrap; gap: var(--space-4); margin-top: var(--space-5); }
.profile-meta span { display: inline-flex; align-items: center; gap: 0.4rem; color: var(--color-text-muted); font-size: var(--text-sm); }
.profile-actions { align-self: end; }
.profile-metrics { border-block: 1px solid var(--color-border); background: var(--color-surface); }
.profile-metrics__grid { display: grid; grid-template-columns: repeat(6, 1fr); }
.profile-metrics__grid > div { display: grid; grid-template-columns: auto 1fr; grid-template-rows: auto auto; align-items: center; gap: 0.1rem 0.65rem; border-right: 1px solid var(--color-border); padding: var(--space-5); }
.profile-metrics__grid > div:last-child { border-right: 0; }
.profile-metrics svg { grid-row: 1 / 3; color: var(--color-brand-500); }
.profile-metrics strong { font-size: var(--text-lg); }
.profile-metrics span { color: var(--color-text-muted); font-size: var(--text-xs); }
.profile-content { min-height: 36rem; }
.profile-tabs { display: flex; overflow-x: auto; gap: var(--space-2); margin-bottom: var(--space-8); border-bottom: 1px solid var(--color-border); }
.profile-tabs button { display: inline-flex; min-width: max-content; align-items: center; gap: 0.5rem; border: 0; border-bottom: 3px solid transparent; background: transparent; color: var(--color-text-secondary); font-weight: 800; padding: 0.9rem 1rem; cursor: pointer; }
.profile-tabs button > span { border-radius: var(--radius-pill); background: var(--color-brand-50); color: var(--color-brand-700); font-size: 0.7rem; padding: 0.22rem 0.5rem; }
.profile-tabs button.active { border-bottom-color: var(--color-brand-700); color: var(--color-brand-800); }
.profile-list { display: grid; gap: var(--space-4); }
.profile-card { display: flex; gap: var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-sm); padding: var(--space-5); }
.profile-card__icon { display: grid; width: 3rem; height: 3rem; flex: 0 0 auto; place-items: center; border-radius: 50%; background: var(--color-brand-50); color: var(--color-brand-600); }
.profile-card__icon--score { background: #fff7e8; color: var(--color-warning); font-size: var(--text-lg); font-weight: 900; }
.profile-card__body { min-width: 0; flex: 1; }
.profile-card__body > a,
.profile-card__body > strong { color: var(--color-brand-800); font-size: var(--text-lg); font-weight: 800; text-decoration: none; }
.profile-card__body > a:hover { text-decoration: underline; }
.profile-card__body p { margin: 0.65rem 0; color: var(--color-text-secondary); line-height: 1.75; white-space: pre-wrap; }
.profile-card__body small { display: flex; align-items: center; gap: 0.3rem; color: var(--color-text-muted); }
.chip-row { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-top: 0.7rem; }
.chip-row span { border-radius: var(--radius-pill); background: var(--color-brand-50); color: var(--color-brand-700); font-size: var(--text-xs); font-weight: 700; padding: 0.35rem 0.6rem; }
.resource-cover { display: grid; width: 4rem; height: 5.2rem; flex: 0 0 auto; place-items: center; overflow: hidden; border-radius: var(--radius-md); background: var(--color-brand-50); color: var(--color-brand-600); }
.resource-cover img { width: 100%; height: 100%; object-fit: cover; }

@media (max-width: 899px) {
  .profile-hero__inner { grid-template-columns: auto 1fr; }
  .profile-actions { grid-column: 2; justify-self: start; }
  .profile-metrics__grid { grid-template-columns: repeat(3, 1fr); }
  .profile-metrics__grid > div:nth-child(3n) { border-right: 0; }
  .profile-metrics__grid > div:nth-child(n + 4) { border-top: 1px solid var(--color-border); }
}

@media (max-width: 599px) {
  .profile-hero__inner { grid-template-columns: 1fr; text-align: center; }
  .profile-avatar { justify-self: center; }
  .profile-meta { justify-content: center; }
  .profile-actions { grid-column: auto; justify-self: center; }
  .profile-metrics__grid { grid-template-columns: repeat(2, 1fr); }
  .profile-metrics__grid > div { border-top: 1px solid var(--color-border); border-right: 1px solid var(--color-border); }
  .profile-metrics__grid > div:nth-child(-n + 2) { border-top: 0; }
  .profile-metrics__grid > div:nth-child(2n) { border-right: 0; }
  .profile-card { padding: var(--space-4); }
}
</style>
