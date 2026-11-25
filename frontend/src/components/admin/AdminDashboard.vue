<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { Activity, ArrowUpRight, BookOpen, ClipboardList, RefreshCw, Ticket, UsersRound } from 'lucide-vue-next'
import { getDashboard, type Dashboard } from '../../api/admin'
import BaseButton from '../ui/BaseButton.vue'
const data = ref<Dashboard | null>(null)
const error = ref('')
const loading = ref(false)
const highlights = [
  { key: 'pendingResourceCount', label: '待审资源', icon: ClipboardList, to: '/admin/console/resources' },
  { key: 'userCount', label: '用户总数', icon: UsersRound, to: '/admin/console/users' },
  { key: 'publishedGameCount', label: '已发布游戏', icon: BookOpen, to: '/admin/catalog' },
  { key: 'todayDrawCount', label: '今日抽奖', icon: Ticket, to: '/admin/console/prizes' },
] as const
const groups = [
  { name: '社区', items: [['creatorCount', '创作者'], ['adminCount', '管理员'], ['hiddenCommentCount', '已隐藏评论'], ['hiddenReviewCount', '已隐藏评价']] },
  { name: '作品与资源', items: [['gameCount', '游戏总数'], ['offlineGameCount', '已下架游戏'], ['activeResourceCount', '有效资源']] },
  { name: '签到与奖池', items: [['currentMonthPrizeCount', '当月奖品'], ['drawRecordCount', '中奖记录']] },
] as const

const numberFormat = new Intl.NumberFormat('zh-CN')
function count(value: number | null | undefined): string {
  return value == null ? '—' : numberFormat.format(value)
}
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getDashboard(request.signal)
    if (!request.signal.aborted) data.value = result
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '仪表盘加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
void load()
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="work-stack">
    <div class="work-toolbar">
      <h2>站点运行概览</h2>
      <BaseButton variant="secondary" size="small" :disabled="loading" @click="load"><RefreshCw :size="16" aria-hidden="true" />刷新概览</BaseButton>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取统计…</p>
    <template v-if="data">
      <div class="dashboard-highlights">
        <RouterLink v-for="item in highlights" :key="item.key" :to="item.to" class="work-card dashboard-highlight">
          <span class="dashboard-highlight__top"><span><component :is="item.icon" :size="20" aria-hidden="true" /></span><ArrowUpRight :size="17" aria-hidden="true" /></span>
          <span class="work-muted">{{ item.label }}</span>
          <strong class="dashboard-value">{{ count(data[item.key]) }}</strong>
        </RouterLink>
      </div>
      <div class="dashboard-groups">
        <article v-for="group in groups" :key="group.name" class="work-card dashboard-group">
          <h3>{{ group.name }}</h3>
          <dl>
            <div v-for="[key, label] in group.items" :key="key"><dt>{{ label }}</dt><dd>{{ count(data[key]) }}</dd></div>
          </dl>
        </article>
      </div>
      <aside class="work-card dashboard-sync">
        <div><Activity :size="21" aria-hidden="true" /><div><h3>统计同步</h3><p class="work-muted">{{ data.redisAvailable ? '同步服务可用' : '同步服务暂不可用' }} · 待同步 {{ count(data.pendingRedisOperations) }} 项</p></div></div>
        <BaseButton to="/admin/operations" variant="secondary" size="small">查看维护</BaseButton>
      </aside>
    </template>
  </section>
</template>
<style scoped>
.dashboard-highlights { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .85rem; }
.dashboard-highlight { display: grid; align-content: start; gap: .15rem; padding: 1rem; text-decoration: none; }
.dashboard-highlight:hover { border-color: var(--color-brand-300); box-shadow: var(--shadow-md); }
.dashboard-highlight:first-child { background: linear-gradient(150deg, var(--color-brand-50), var(--color-surface)); }
.dashboard-highlight__top { display: flex; align-items: flex-start; justify-content: space-between; gap: .5rem; margin-bottom: .42rem; color: var(--color-brand-600); }
.dashboard-highlight__top > span { display: grid; width: 2.35rem; height: 2.35rem; place-items: center; border-radius: var(--radius-sm); background: var(--color-brand-100); color: var(--color-brand-800); }
.dashboard-value { font-size: clamp(1.55rem, 2.5vw, 2.35rem); font-variant-numeric: tabular-nums; line-height: 1.3; color: var(--color-brand-800); }
.dashboard-groups { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .85rem; }
.dashboard-group h3 { margin: 0 0 .8rem; color: var(--color-brand-900); }
.dashboard-group dl { display: grid; gap: .42rem; margin: 0; }
.dashboard-group dl > div { display: flex; align-items: start; justify-content: space-between; gap: .7rem; padding-top: .4rem; border-top: 1px solid var(--color-border); font-size: var(--text-sm); }
.dashboard-group dt { color: var(--color-text-secondary); }
.dashboard-group dd { margin: 0; color: var(--color-brand-800); font-weight: 700; font-variant-numeric: tabular-nums; text-align: right; }
.dashboard-sync, .dashboard-sync > div { display: flex; flex-wrap: wrap; align-items: center; gap: .7rem; }
.dashboard-sync { justify-content: space-between; }
.dashboard-sync svg { flex: none; color: var(--color-brand-600); }
.dashboard-sync h3, .dashboard-sync p { margin: 0; }
@media (max-width: 719px) { .dashboard-highlights { grid-template-columns: repeat(2, minmax(0, 1fr)); } .dashboard-groups { grid-template-columns: minmax(0, 1fr); } }
</style>
