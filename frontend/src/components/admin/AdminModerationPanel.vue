<script setup lang="ts">
import { gameRoute, resourceEditRoute, userRoute } from '../../utils/navigation'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  getModeration,
  updateModeration,
  reviewResource,
  type ModerationKind,
  type ModerationItem,
} from '../../api/admin'
import type { PageData } from '../../api/types'
import { parseResourceUrl } from '../../utils/resource-url'
import { formatDate } from '../../utils/format'
import { hasResourceRevision, isResourceConflict, resourceStatusLabel } from '../../utils/resource-review'
import BaseButton from '../ui/BaseButton.vue'
import UserAvatar from '../ui/UserAvatar.vue'
import ActionDialog from '../ui/ActionDialog.vue'
import ResourceHistoryDialog from '../content/ResourceHistoryDialog.vue'
import PaginationControl from '../ui/PaginationControl.vue'
import { useNotificationStore } from '../../stores/notifications'
const props = defineProps<{ kind: ModerationKind }>()
const notices = useNotificationStore()
const keyword = ref(''),
  status = ref(''),
  gameId = ref(''),
  userId = ref(''),
  page = ref(1),
  error = ref(''),
  busy = ref(false),
  loading = ref(false)
const labels = computed(() =>
  props.kind === 'resources' ? ['失效', '有效', '待审核'] : ['隐藏', '显示'],
)
const title = computed(
  () => ({ resources: '资源审核', comments: '评论治理', reviews: '评价治理' })[props.kind],
)
const data = ref<PageData<ModerationItem> | null>(null)
const review = ref<{ item: ModerationItem; status: number } | null>(null)
const reason = ref(''), reviewError = ref(''), reviewConflict = ref(false)
const historyResource = ref<ModerationItem | null>(null)
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getModeration(
      props.kind,
      {
        keyword: keyword.value.trim(),
        status: status.value === '' ? undefined : Number(status.value),
        gameId: gameId.value ? Number(gameId.value) : undefined,
        userId: userId.value ? Number(userId.value) : undefined,
        pageNo,
      },
      request.signal,
    )
    if (!request.signal.aborted) {
      data.value = result
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '内容加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function change(item: ModerationItem, next: number) {
  if (busy.value || loading.value) return
  if (props.kind === 'resources') {
    if (!hasResourceRevision(item.revision)) {
      error.value = '资源信息需要刷新，请重新加载列表后再审核'
      return
    }
    review.value = { item: { ...item }, status: next }
    reason.value = ''
    reviewError.value = ''
    reviewConflict.value = false
    return
  }
  if (!window.confirm(`确认将这条${props.kind === 'comments' ? '评论' : '评价'}设为「${labels.value[next]}」？`)) return
  busy.value = true
  error.value = ''
  try {
    await updateModeration(props.kind, item.id, next)
    notices.show('内容状态已更新', 'success')
    await load(page.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '状态更新失败'
  } finally {
    busy.value = false
  }
}

async function confirmReview(): Promise<void> {
  const draft = review.value
  if (!draft || busy.value || reviewConflict.value || !hasResourceRevision(draft.item.revision)) return
  const explanation = reason.value.trim()
  if ((draft.status === 0 && !explanation) || explanation.length > 500) {
    reviewError.value = draft.status === 0 && !explanation ? '请填写设为失效的原因' : '审核说明最多填写 500 字'
    return
  }
  busy.value = true
  reviewError.value = ''
  try {
    await reviewResource(draft.item.id, { status: draft.status, revision: draft.item.revision, reason: explanation })
    if (review.value !== draft) return
    review.value = null
    notices.show('资源审核结果已保存', 'success')
    await load(page.value)
  } catch (cause) {
    if (review.value !== draft) return
    // 当前确认绑定打开弹窗时的内容版本，冲突后由管理员刷新、核对并重新确认。
    reviewConflict.value = isResourceConflict(cause)
    reviewError.value = reviewConflict.value ? '资源已被更新，请刷新列表，核对内容后重新审核。' : cause instanceof Error ? cause.message : '审核失败'
  } finally { busy.value = false }
}

function refreshReview(): void {
  review.value = null
  void load(page.value)
}
watch(
  () => props.kind,
  () => {
    keyword.value = ''
    status.value = ''
    data.value = null
    review.value = null
    historyResource.value = null
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="work-stack">
    <h2>{{ title }}</h2>
    <form class="work-toolbar" @submit.prevent="load()">
      <input v-model="keyword" aria-label="搜索内容" maxlength="100" placeholder="关键词" />
      <select v-model="status" aria-label="筛选状态">
        <option value="">全部状态</option>
        <option v-for="(label, value) in labels" :key="value" :value="String(value)">
          {{ label }}
        </option>
      </select>
      <input
        v-model="gameId"
        aria-label="筛选游戏编号"
        type="number"
        min="1"
        max="9007199254740991"
        placeholder="游戏编号"
      />
      <input
        v-model="userId"
        aria-label="筛选用户编号"
        type="number"
        min="1"
        max="9007199254740991"
        placeholder="用户编号"
      />
      <BaseButton type="submit" variant="secondary" :disabled="busy || loading">
        筛选内容
      </BaseButton>
    </form>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在加载内容…</p>
    <div class="work-stack">
      <article v-for="item in data?.list ?? []" :key="item.id" class="work-card">
        <div class="work-toolbar">
          <h3>{{ item.name || (kind === 'resources' ? '分享的资源' : kind === 'comments' ? '社区评论' : '作品评价') }}</h3>
          <span class="work-badge">{{ labels[item.status] || '未知状态' }}</span>
        </div>
        <p class="work-muted">
          <RouterLink :to="gameRoute(item.gameId)">
            {{ item.gameName || '查看所属作品' }}
          </RouterLink>
          ·
          <RouterLink class="moderation-author" :to="userRoute(item.userId)">
            <UserAvatar :src="item.avatarUrl" />
            {{ item.username || '查看作者主页' }}
          </RouterLink>
          · {{ formatDate(item.createTime) }}
        </p>
        <template v-if="kind === 'resources'">
          <p>
            {{ item.types }} · {{ item.languages }} · {{ item.platforms }} · {{ item.fileSize }}
          </p>
          <p class="work-text">{{ item.remark }}</p>
          <p v-if="item.reviewReason" class="work-text">最近审核说明：{{ item.reviewReason }}</p>
          <p v-if="item.reviewedAt" class="work-muted">最近审核于 {{ formatDate(item.reviewedAt) }}</p>
          <details>
            <summary>查看资源链接和访问口令</summary>
            <p>
              <a
                v-if="parseResourceUrl(item.downloadUrl)"
                :href="parseResourceUrl(item.downloadUrl)!.href"
                target="_blank"
                rel="noopener noreferrer"
              >
                {{ item.downloadUrl }}
              </a>
              <span v-else>链接格式不可用</span>
            </p>
            <p>提取码 {{ item.extractCode || '无' }} · 解压密码 {{ item.password || '无' }}</p>
          </details>
        </template>
        <template v-else>
          <p v-if="kind === 'reviews'" class="work-muted">
            评分 {{ item.score ?? '—' }} · 剧透级别 {{ item.spoilerLevel ?? '—' }}
          </p>
          <details v-if="(item.spoilerLevel ?? 1) > 1">
            <summary>展开含剧透的内容</summary>
            <p class="work-text">{{ item.content || '无附加正文' }}</p>
          </details>
          <p v-else class="work-text">{{ item.content || '无附加正文' }}</p>
        </template>
        <div class="work-toolbar">
          <BaseButton
            v-for="(label, next) in labels"
            :key="next"
            size="small"
            variant="secondary"
            :disabled="busy || loading || item.status === next"
            @click="change(item, next)"
          >
            设为{{ label }}
          </BaseButton>
          <template v-if="kind === 'resources'">
            <BaseButton :to="resourceEditRoute(item.id)" variant="ghost" size="small" :disabled="busy || loading">编辑资源</BaseButton>
            <BaseButton variant="ghost" size="small" :disabled="busy || loading" @click="historyResource = item">编辑与审核记录</BaseButton>
          </template>
        </div>
      </article>
    </div>
    <p v-if="!loading && !data?.list.length" class="work-empty">暂无匹配内容。</p>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
    <ActionDialog v-if="review" title="确认资源审核" :busy="busy" @close="review = null">
      <form class="work-form" @submit.prevent="confirmReview">
        <h3>{{ review.item.name || '分享的资源' }}</h3>
        <p class="work-muted">{{ resourceStatusLabel(review.item.status) }} → {{ resourceStatusLabel(review.status) }}</p>
        <label>
          {{ review.status === 0 ? '失效原因' : '审核说明（选填）' }}
          <textarea v-model="reason" :required="review.status === 0" maxlength="500" rows="4" :disabled="busy || reviewConflict" />
        </label>
        <p class="work-muted">作者可以看到这段说明。{{ reason.length }} / 500</p>
        <p v-if="reviewError" class="work-error" role="alert">{{ reviewError }}</p>
        <div class="work-toolbar">
          <BaseButton type="submit" :loading="busy" :disabled="reviewConflict">确认设为{{ resourceStatusLabel(review.status) }}</BaseButton>
          <BaseButton v-if="reviewConflict" variant="secondary" @click="refreshReview">刷新列表</BaseButton>
          <BaseButton variant="ghost" :disabled="busy" @click="review = null">取消</BaseButton>
        </div>
      </form>
    </ActionDialog>
    <ResourceHistoryDialog v-if="historyResource" :resource-id="historyResource.id" :resource-name="historyResource.name" admin @close="historyResource = null" />
  </section>
</template>

<style scoped>
.moderation-author { display: inline-flex; align-items: center; gap: .45rem; vertical-align: middle; --user-avatar-size: 1.65rem; }
</style>
