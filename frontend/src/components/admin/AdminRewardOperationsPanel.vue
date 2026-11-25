<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { changeRewardMode, getRewardStatus, getRewardOutbox, retryRewardEvent,
  type RewardStatus, type RewardOutboxOverview, type RewardAction } from '../../api/admin-rewards'
import BaseButton from '../ui/BaseButton.vue'

const busy = defineModel<boolean>('busy', { default: false })
const status = ref<RewardStatus | null>(null)
const outbox = ref<RewardOutboxOverview | null>(null)
const loading = ref(false)
const error = ref('')
const message = ref('')
const modes = { LEGACY: '旧积分流程', DRAINING: '排空与基线核对', ACTIVE: 'MySQL 事务流程' }
const states: Record<string, string> = { PENDING: '待投递', IN_FLIGHT: '发送中', SENT: '待消费', COMPLETE: '已完成', FAILED: '等待重试' }
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const [current, events] = await Promise.all([getRewardStatus(request.signal), getRewardOutbox(request.signal)])
    if (request.signal.aborted) return
    status.value = current
    outbox.value = events
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '积分运行状态读取失败'
  } finally { if (!request.signal.aborted) loading.value = false }
}
async function change(action: RewardAction) {
  const confirmations: Record<RewardAction, string> = {
    drain: '进入排空阶段后，新的签到、抽奖和奖池维护会暂时停用。确认开始？',
    baseline: '核对下一批最多25个用户的余额和签到到账凭据。发现差异会停止，确认执行？',
    activate: '确认已停止所有旧版 Java 实例并完成核对？激活后由 MySQL 事务处理积分和库存，旧版镜像不支持此模式。',
    'cancel-drain': '取消排空并恢复旧积分流程？',
  }
  if (busy.value || !window.confirm(confirmations[action])) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    status.value = await changeRewardMode(action)
    message.value = '操作完成，请核对当前状态。'
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '积分切换失败' }
  finally { busy.value = false }
}
async function retry(id: string) {
  if (busy.value || !window.confirm(`确认提前重试通知 #${id}？同一事件会执行幂等投影。`)) return
  busy.value = true
  error.value = ''
  try { await retryRewardEvent(id); await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '通知重试失败' }
  finally { busy.value = false }
}
onBeforeUnmount(() => controller?.abort())
void load()
</script>

<template>
  <section class="work-card work-stack">
    <div class="work-toolbar">
      <h2>签到与抽奖事务</h2>
      <BaseButton variant="secondary" :disabled="busy || loading" @click="load">刷新状态</BaseButton>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="message" class="work-success" role="status">{{ message }}</p>
    <p v-if="status">当前运行于{{ modes[status.mode] }}。</p>
    <p v-if="status && !status.schemaReady" class="work-muted">V19 结构尚未就绪，请完成数据库迁移并重启新版 Java。</p>
    <template v-if="status?.schemaReady">
      <div v-if="status.mode !== 'ACTIVE'" class="work-stack">
        <p class="work-muted">先排空旧消息与脏任务，再核对余额、库存和签到到账凭据。新流程激活后，余额、流水、库存和中奖记录一起提交。</p>
        <p v-if="status.mode === 'DRAINING'">基线扫描至用户 #{{ status.baselineCursor }}，{{ status.baselineComplete ? '本轮已完成' : '仍需继续核对' }}。</p>
        <ul v-if="status.blockers?.length"><li v-for="blocker in status.blockers" :key="blocker">{{ blocker }}</li></ul>
        <div class="work-toolbar">
          <BaseButton v-if="status.mode === 'LEGACY'" :disabled="busy || loading" @click="change('drain')">开始排空旧流程</BaseButton>
          <template v-if="status.mode === 'DRAINING'">
            <BaseButton :disabled="busy || loading || !!status.blockers?.length" @click="change('baseline')">核对下一批基线</BaseButton>
            <BaseButton :disabled="busy || loading || !status.readyToActivate" @click="change('activate')">激活事务流程</BaseButton>
            <BaseButton variant="secondary" :disabled="busy || loading" @click="change('cancel-drain')">取消排空</BaseButton>
          </template>
        </div>
      </div>
      <div class="work-toolbar">
        <span v-for="item in outbox?.counts" :key="item.state">{{ states[item.state] ?? item.state }} {{ item.count }}</span>
      </div>
      <div v-if="outbox?.recent.length" class="work-table-wrap">
        <table class="work-table">
          <thead><tr><th>通知</th><th>状态</th><th>发送次数</th><th>最近错误类型</th><th>操作</th></tr></thead>
          <tbody><tr v-for="event in outbox.recent" :key="event.id">
            <td>#{{ event.id }}</td><td>{{ states[event.state] ?? event.state }}</td><td>{{ event.attempts }}</td>
            <td>{{ event.last_error ?? '—' }}</td>
            <td><button v-if="!event.projected_at" class="work-link" :disabled="busy || loading || event.state === 'IN_FLIGHT'" @click="retry(event.id)">提前重试</button><span v-else>已确认</span></td>
          </tr></tbody>
        </table>
      </div>
      <p v-else class="work-muted">当前尚无事务通知。</p>
    </template>
  </section>
</template>
