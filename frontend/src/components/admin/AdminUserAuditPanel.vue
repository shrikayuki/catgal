<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import {
  getContentOperations,
  getPrizeOperations,
  getRedisOperations,
  getResourceOperations,
  getUserOperations,
  type ContentAuditTarget,
  type ContentOperationLog,
  type PrizeOperationLog,
  type RedisAuditOutcome,
  type RedisOperationLog,
  type ResourceOperationLog,
  type UserOperationLog,
} from '../../api/admin'
import type { PageData } from '../../api/types'
import { formatAccountTime } from '../../utils/account-status'
import { resourceActionLabel, resourceStatusLabel } from '../../utils/resource-review'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'

const auditScopes = {
  users: {
    label: '用户操作', target: '目标用户', filter: '目标用户编号',
    description: '查看角色调整、封禁与解封的操作者、原因和状态变化，时间为北京时间。',
  },
  content: {
    label: '内容操作', target: '目标内容', filter: '目标内容编号',
    description: '查看评论和评价的隐藏、恢复记录。时间为北京时间。',
  },
  resources: {
    label: '资源审核', target: '目标资源', filter: '资源编号',
    description: '查看资源审核结果、状态变化和审核原因。时间为北京时间。',
  },
  prizes: {
    label: '奖池操作', target: '目标奖品', filter: '奖品编号',
    description: '查看奖品新增、编辑、启用和停用记录。时间为北京时间。',
  },
  redis: {
    label: 'Redis 运维', target: '操作对象', filter: '游戏或资源编号',
    description: '查看游戏统计、资源下载计数和全站计数同步的执行结果。时间为北京时间。',
  },
} as const
type AuditScope = keyof typeof auditScopes
type AuditLog = UserOperationLog | ContentOperationLog | ResourceOperationLog | PrizeOperationLog | RedisOperationLog

const scope = ref<AuditScope>('users')
const emptyFilters = { admin: '', target: '', targetType: '' as ContentAuditTarget | '', operation: '', outcome: '' as RedisAuditOutcome | '', requestId: '', from: '', to: '' }
const filters = reactive({ ...emptyFilters })
let applied = { ...filters }
const data = ref<PageData<AuditLog> | null>(null)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const selected = ref<AuditLog | null>(null)
const userOperations = { USER_BAN: '封禁用户', USER_UNBAN: '解封用户', USER_ROLE_CHANGE: '修改角色' }
const contentOperations = { COMMENT_HIDE: '隐藏评论', COMMENT_RESTORE: '恢复评论', REVIEW_HIDE: '隐藏评价', REVIEW_RESTORE: '恢复评价' }
const resourceOperations = {
  APPROVE: resourceActionLabel('APPROVE'),
  REJECT: resourceActionLabel('REJECT'),
  REQUEUE: resourceActionLabel('REQUEUE'),
}
const prizeOperations = { PRIZE_CREATE: '新增奖品', PRIZE_UPDATE: '编辑奖品', PRIZE_ENABLE: '启用奖品', PRIZE_DISABLE: '停用奖品' }
const redisOperations = { GAME_STATS_REPAIR: '修复游戏统计', RESOURCE_DOWNLOAD_REPAIR: '修复资源下载计数', COUNTERS_SYNC: '同步全站计数' }
const outcomeLabels = {
  COMPLETED: { label: '已完成', description: '此次调用已完成。' },
  FAILED: { label: '执行出错', description: '执行过程中出错，部分变更可能已经生效。' },
  UNCONFIRMED: { label: '结果未确认', description: '暂时无法确认此次操作的执行结果。' },
} satisfies Record<RedisAuditOutcome, { label: string; description: string }>
const operationLabels = { ...userOperations, ...contentOperations, ...resourceOperations, ...prizeOperations, ...redisOperations }
const activeScope = computed(() => auditScopes[scope.value])
const isContent = computed(() => scope.value === 'content')
const isResource = computed(() => scope.value === 'resources')
const isPrize = computed(() => scope.value === 'prizes')
const isRedis = computed(() => scope.value === 'redis')
const showReason = computed(() => scope.value === 'users' || scope.value === 'resources')
const columnCount = computed(() => showReason.value || isRedis.value ? 6 : 5)
const operations = computed(() => {
  if (isResource.value) return Object.entries(resourceOperations)
  if (isPrize.value) return Object.entries(prizeOperations)
  if (isRedis.value) return Object.entries(redisOperations)
  if (!isContent.value) return Object.entries(userOperations)
  const prefix = filters.targetType.toUpperCase()
  return Object.entries(contentOperations).filter(([key]) => key.startsWith(prefix))
})
// 与后端审计字段白名单对应，新增字段时一并补充可读名称和值格式。
const accountFields: Record<string, string> = { id: '用户编号', username: '用户名', role: '角色', status: '有效状态', storedStatus: '记录状态', banReason: '封禁原因', bannedUntil: '截止时间' }
const contentFields: Record<string, string> = { status: '内容状态', gameId: '所属游戏编号' }
const resourceFields: Record<string, string> = { status: '资源状态', gameId: '所属游戏编号', revision: '记录版本' }
const prizeFields: Record<string, string> = {
  prizeName: '奖品名称', prizeType: '奖品类型', month: '奖池月份', status: '启用状态',
  weight: '权重', totalStock: '总库存', remainStock: '剩余库存', revision: '记录版本',
}
const prizeTypes = ['', '萌萌点', '道具', '实物', '谢谢参与']
const fieldsByScope = { users: accountFields, content: contentFields, resources: resourceFields, prizes: prizeFields }
const fields = computed(() => scope.value === 'redis' ? undefined : fieldsByScope[scope.value])
let controller: AbortController | undefined

function targetReference(row: AuditLog): string {
  let name = '用户'
  if ('targetType' in row) {
    const names = { comment: '评论', review: '评价', resource: '资源', prize: '奖品', game: '游戏', global: '全局同步' }
    name = names[row.targetType]
  }
  return row.targetId == null ? name : `${name} #${row.targetId}`
}

function prizeName(row: AuditLog): string | undefined {
  if (!('targetType' in row) || row.targetType !== 'prize') return undefined
  const name = row.afterData?.prizeName ?? row.beforeData?.prizeName
  return typeof name === 'string' && name ? name : undefined
}

function showValue(key: string, value: unknown): string {
  if (value == null || value === '') return '—'
  if (key === 'role') return ['社区成员', '创作者', '管理员'][Number(value)] ?? String(value)
  if (isPrize.value) {
    if (key === 'prizeType') return prizeTypes[Number(value)] || '未知类型'
    if (key === 'month') {
      const month = /^(\d{4})(0[1-9]|1[0-2])$/.exec(String(value))
      if (month) return `${month[1]} 年 ${Number(month[2])} 月`
    }
    if (key === 'status') {
      if (Number(value) === 1) return '启用'
      if (Number(value) === 0) return '停用'
      return '未知状态'
    }
  }
  if (key === 'status' || key === 'storedStatus') {
    if (isResource.value) return resourceStatusLabel(Number(value))
    if (Number(value) === 1) return '正常'
    if (Number(value) === 0) return isContent.value ? '已隐藏' : '封禁'
    return '未知状态'
  }
  if (key === 'bannedUntil') return formatAccountTime(String(value))
  return String(value)
}

function applyFilters() {
  // 编号保留十进制字符串，避免超出 JavaScript 安全整数后被四舍五入。
  for (const value of [filters.admin, filters.target]) {
    if (value && (!/^[1-9]\d{0,18}$/.test(value) || (value.length === 19 && value > '9223372036854775807'))) {
      error.value = '请输入有效的编号'
      return
    }
  }
  if (filters.from && filters.to && filters.from > filters.to) {
    error.value = '结束时间不能早于开始时间'
    return
  }
  applied = { ...filters }
  void load()
}

function selectScope(next: AuditScope) {
  if (scope.value === next) return
  scope.value = next
  Object.assign(filters, emptyFilters)
  applied = { ...filters }
  data.value = null
  selected.value = null
  void load()
}

async function load(pageNo = 1) {
  // 切换筛选和页码后，以最新请求为准，避免旧请求覆盖当前列表。
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    // 查询时间显式附加北京时间偏移，浏览器所在时区变化时仍按站点时间筛选。
    const query = {
      pageNo,
      adminUserId: applied.admin || undefined,
      operation: applied.operation || undefined,
      from: applied.from ? new Date(applied.from + ':00+08:00').toISOString() : undefined,
      to: applied.to ? new Date(applied.to + ':00+08:00').toISOString() : undefined,
    }
    const targetId = applied.target || undefined
    const requestId = applied.requestId.trim() || undefined
    let result: PageData<AuditLog>
    // 资源审核历史只读取审核快照，资源删除后仍可查看，也没有追踪编号可筛选。
    if (isResource.value) {
      result = await getResourceOperations({ ...query, targetId }, request.signal)
    } else if (isPrize.value) {
      result = await getPrizeOperations({ ...query, requestId, targetId }, request.signal)
    } else if (isRedis.value) {
      result = await getRedisOperations({ ...query, requestId, targetId, outcome: applied.outcome || undefined }, request.signal)
    } else if (isContent.value) {
      result = await getContentOperations({ ...query, requestId, targetType: applied.targetType || undefined, targetId }, request.signal)
    } else {
      result = await getUserOperations({ ...query, requestId, targetUserId: targetId }, request.signal)
    }
    if (!request.signal.aborted) { data.value = result; page.value = pageNo }
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '操作记录读取失败'
  } finally { if (!request.signal.aborted) loading.value = false }
}
void load()
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="work-stack">
    <div class="work-toolbar audit-scopes" role="group" aria-label="操作记录范围">
      <BaseButton v-for="(option, key) in auditScopes" :key="key" size="small" :variant="scope === key ? 'primary' : 'secondary'" :aria-pressed="scope === key" @click="selectScope(key)">{{ option.label }}</BaseButton>
    </div>
    <div><h2>{{ activeScope.label }}记录</h2><p class="work-muted">{{ activeScope.description }}</p></div>
    <form class="work-toolbar" @submit.prevent="applyFilters">
      <input v-model.trim="filters.admin" inputmode="numeric" maxlength="19" placeholder="管理员编号" aria-label="管理员编号" />
      <select v-if="isContent" v-model="filters.targetType" aria-label="内容类型" @change="filters.operation = ''">
        <option value="">全部内容</option><option value="comment">评论</option><option value="review">评价</option>
      </select>
      <input v-model.trim="filters.target" inputmode="numeric" maxlength="19" :placeholder="activeScope.filter" :aria-label="activeScope.filter" />
      <select v-model="filters.operation" aria-label="操作类型"><option value="">全部操作</option><option v-for="[key, label] in operations" :key="key" :value="key">{{ label }}</option></select>
      <select v-if="isRedis" v-model="filters.outcome" aria-label="执行结果">
        <option value="">全部结果</option><option v-for="(outcome, key) in outcomeLabels" :key="key" :value="key">{{ outcome.label }}</option>
      </select>
      <input v-if="!isResource" v-model="filters.requestId" maxlength="64" placeholder="追踪编号" aria-label="追踪编号" />
      <label>开始时间<input v-model="filters.from" type="datetime-local" /></label>
      <label>结束时间<input v-model="filters.to" type="datetime-local" /></label>
      <BaseButton type="submit" :disabled="loading">查询记录</BaseButton>
    </form>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取操作记录…</p>
    <div class="work-table-wrap">
      <table class="work-table audit-list">
        <thead><tr><th>时间（北京时间）</th><th>操作者</th><th>{{ activeScope.target }}</th><th>操作</th><th v-if="isRedis">执行结果</th><th v-if="showReason">原因</th><th>详情</th></tr></thead>
        <tbody>
          <tr v-for="row in data?.list ?? []" :key="row.id">
            <td data-label="时间">{{ formatAccountTime(row.createTime) }}</td>
            <td data-label="操作者">管理员 #{{ row.adminUserId }}</td>
            <td :data-label="activeScope.target">
              <strong v-if="prizeName(row)">{{ prizeName(row) }}</strong>
              <span :class="{ 'audit-target-ref': prizeName(row) }">{{ targetReference(row) }}</span>
            </td>
            <td data-label="操作">{{ operationLabels[row.operation] ?? '其他操作' }}</td>
            <td v-if="'outcome' in row" data-label="执行结果"><span class="work-badge audit-outcome" :data-outcome="row.outcome">{{ outcomeLabels[row.outcome]?.label ?? '未知结果' }}</span></td>
            <td v-if="showReason" class="audit-reason" data-label="原因">{{ row.reason || '—' }}</td>
            <td class="audit-list__details"><BaseButton size="small" @click="selected = row">{{ isRedis ? '查看详情' : '查看变化' }}</BaseButton></td>
          </tr>
          <tr v-if="!loading && !error && !data?.list.length"><td class="audit-list__empty" :colspan="columnCount">暂无匹配的{{ activeScope.label }}记录。</td></tr>
        </tbody>
      </table>
    </div>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
    <ActionDialog v-if="selected" :title="`${activeScope.label}详情`" @close="selected = null">
      <div class="work-stack">
        <p>{{ operationLabels[selected.operation] ?? '其他操作' }} · 管理员 #{{ selected.adminUserId }} · {{ targetReference(selected) }}</p>
        <p>{{ formatAccountTime(selected.createTime) }}</p>
        <!-- 运维记录只显示已知的结果说明，避免快照或报错原文暴露内部键。 -->
        <div v-if="'outcome' in selected" class="audit-outcome-detail">
          <span class="work-badge audit-outcome" :data-outcome="selected.outcome">{{ outcomeLabels[selected.outcome]?.label ?? '未知结果' }}</span>
          <p class="work-muted">{{ outcomeLabels[selected.outcome]?.description }}</p>
        </div>
        <p v-else-if="selected.reason" class="audit-reason">操作原因 · {{ selected.reason }}</p>
        <div v-if="fields" class="work-table-wrap"><table class="work-table audit-changes"><thead><tr><th>字段</th><th>操作前</th><th>操作后</th></tr></thead>
          <tbody><tr v-for="(label, key) in fields" :key="key"><td>{{ label }}</td><td><span class="audit-value-label">操作前</span>{{ showValue(key, selected.beforeData?.[key]) }}</td><td><span class="audit-value-label">操作后</span>{{ showValue(key, selected.afterData?.[key]) }}</td></tr></tbody>
        </table></div>
        <p v-if="selected.requestId" class="audit-trace">追踪编号 · {{ selected.requestId }}</p>
        <p v-if="!isRedis && selected.ipAddress" class="audit-trace">连接来源 · {{ selected.ipAddress }}</p>
      </div>
    </ActionDialog>
  </section>
</template>
<style scoped>
.audit-reason { max-width: 24rem; white-space: normal; overflow-wrap: anywhere; }
.audit-target-ref { display: block; color: var(--color-text-muted); font-size: var(--text-xs); }
.audit-outcome[data-outcome='FAILED'] { background: color-mix(in srgb, var(--color-danger) 10%, var(--color-surface)); color: var(--color-danger); }
.audit-outcome[data-outcome='UNCONFIRMED'] { background: var(--color-surface-soft); color: var(--color-text-secondary); }
.audit-outcome-detail { padding: .85rem 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-soft); }
.audit-outcome-detail p { margin: .45rem 0 0; }
.audit-changes { min-width: 0; }
.audit-value-label { display: none; }
.audit-trace { overflow-wrap: anywhere; color: var(--color-text-muted); font-size: .85rem; }
@media (max-width: 720px) {
  .audit-list, .audit-list tbody { display: block; min-width: 0; }
  .audit-list thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
  .audit-list tr { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border-bottom: 1px solid var(--color-border); }
  .audit-list tr:last-child { border-bottom: 0; }
  .audit-list td { min-width: 0; white-space: normal; overflow-wrap: anywhere; border: 0; }
  .audit-list td[data-label]::before { display: block; margin-bottom: .15rem; color: var(--color-text-muted); font-size: var(--text-xs); content: attr(data-label); }
  .audit-list__details, .audit-list__empty { grid-column: 1 / -1; }
}
@media (max-width: 600px) {
  .audit-changes, .audit-changes tbody { display: block; }
  .audit-changes thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
  .audit-changes tr { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); border-bottom: 1px solid var(--color-border); }
  .audit-changes tr:last-child { border-bottom: 0; }
  .audit-changes td { min-width: 0; white-space: normal; overflow-wrap: anywhere; border: 0; }
  .audit-changes td:first-child { grid-column: 1 / -1; padding-bottom: 0; font-weight: 700; }
  .audit-value-label { display: block; margin-bottom: .3rem; font-size: .75rem; font-weight: 400; color: var(--color-text-muted); }
}
</style>
