<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { getAdminUser, updateUserStatus, type AdminUser } from '../../api/admin'
import { ApiError } from '../../api/types'
import { banDeadline, formatAccountTime } from '../../utils/account-status'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'
import UserAvatar from '../ui/UserAvatar.vue'

const props = defineProps<{ user: AdminUser }>()
const emit = defineEmits<{ close: []; saved: []; refreshed: [] }>()
// 对话框保存当前核对的账号状态，冲突后刷新并保留管理员输入的原因。
const current = ref(props.user)
// 打开对话框时确定本次动作，后续状态刷新保持管理员原先选择的封禁或解封意图。
const targetStatus: 0 | 1 = props.user.status === 0 ? 1 : 0
const reason = ref('')
const expiry = ref<'permanent' | 'timed'>('permanent')
const deadline = ref('')
const busy = ref(false)
const error = ref('')
const needsRefresh = ref(false)
const request = new AbortController()

// 刷新只执行查询，成功后同步父列表，下一次写入仍由管理员主动确认。
async function refresh() {
  busy.value = true
  try {
    current.value = await getAdminUser(props.user.id, request.signal)
    needsRefresh.value = false
    emit('refreshed')
    error.value = '已重新读取当前状态，请核对后再提交。'
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '当前状态读取失败'
  } finally { busy.value = false }
}

async function submit() {
  if (busy.value || needsRefresh.value) return
  error.value = ''
  if (!reason.value.trim()) { error.value = '请填写操作原因'; return }
  // datetime-local 输入按北京时间解析，转换成带时区的时间值交给后端核验。
  const until = targetStatus === 0 && expiry.value === 'timed' ? banDeadline(deadline.value) : null
  if (targetStatus === 0 && expiry.value === 'timed' && !until) {
    error.value = '请选择晚于当前时间的北京时间'; return
  }
  busy.value = true
  try {
    await updateUserStatus(current.value.id, { status: targetStatus, reason: reason.value.trim(),
      bannedUntil: until, stateVersion: current.value.stateVersion }, request.signal)
    emit('saved')
  } catch (cause) {
    if (request.signal.aborted) return
    error.value = cause instanceof Error ? cause.message : '操作未完成，请稍后重试'
    if (cause instanceof ApiError && (cause.status === 0 || cause.status === 409 || cause.status >= 500)) {
      // 结果不明确时只重新查询状态，保留原因草稿，等待管理员核对后主动提交。
      needsRefresh.value = true
      await refresh()
    }
  } finally { busy.value = false }
}
// 关闭组件时结束本组件等待。请求已到达服务端时，状态和审计仍按服务端事务完成。
onBeforeUnmount(() => request.abort())
</script>
<template>
  <ActionDialog :title="targetStatus === 0 ? '封禁用户' : '解封用户'" :busy="busy" @close="emit('close')">
    <form class="work-form" @submit.prevent="submit">
      <div class="work-notice">
        <div class="status-user"><UserAvatar :src="current.avatarUrl" /><strong>{{ current.username }}</strong></div>
        <p>当前角色 · {{ ['社区成员', '创作者', '管理员'][current.role] }}</p>
        <p>当前状态 · {{ current.status === 0 ? '已封禁' : '正常' }}<span v-if="current.banExpired">，前次封禁已到期</span></p>
        <p v-if="current.status === 0">封禁期限 · {{ formatAccountTime(current.bannedUntil) }}</p>
        <p v-if="current.banReason">最近原因 · {{ current.banReason }}</p>
      </div>
      <p v-if="error" class="work-error" role="alert">{{ error }}</p>
      <fieldset class="account-fields" :disabled="busy">
        <template v-if="targetStatus === 0">
          <label>封禁期限<select v-model="expiry"><option value="permanent">永久封禁</option><option value="timed">指定截止时间</option></select></label>
          <label v-if="expiry === 'timed'">截止时间（北京时间）<input v-model="deadline" type="datetime-local" required /></label>
        </template>
        <label>操作原因<textarea v-model="reason" rows="3" maxlength="255" required :placeholder="targetStatus === 0 ? '说明封禁原因' : '说明解封原因'" /></label>
        <p class="work-muted">{{ targetStatus === 0 ? '提交后将立即拦截该账号的登录和已有登录凭证请求。' : '解封后该账号可恢复访问。' }}操作会保存到用户操作记录。</p>
        <div class="work-toolbar">
          <BaseButton v-if="needsRefresh" type="button" @click="refresh">重新读取状态</BaseButton>
          <BaseButton type="submit" :disabled="busy || needsRefresh">{{ busy ? '正在处理…' : targetStatus === 0 ? '确认封禁' : '确认解封' }}</BaseButton>
          <BaseButton type="button" variant="secondary" @click="emit('close')">取消</BaseButton>
        </div>
      </fieldset>
    </form>
  </ActionDialog>
</template>
<style scoped>
.status-user { display: flex; align-items: center; gap: .65rem; --user-avatar-size: 2rem; }
.account-fields { display: grid; gap: 1rem; border: 0; padding: 0; min-width: 0; }
.account-fields label { display: grid; gap: .5rem; }
.account-fields input, .account-fields select, .account-fields textarea { width: 100%; }
.work-notice p { margin-top: .4rem; overflow-wrap: anywhere; }
</style>
