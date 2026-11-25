<script setup lang="ts">
import { userRoute } from '../../utils/navigation'
import { onBeforeUnmount, reactive, ref } from 'vue'
import { getAdminUsers, updateUserRole, type AdminUser } from '../../api/admin'
import type { PageData } from '../../api/types'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'
import { formatDate } from '../../utils/format'
import BaseButton from '../ui/BaseButton.vue'
import UserAvatar from '../ui/UserAvatar.vue'
import PaginationControl from '../ui/PaginationControl.vue'
import AdminUserStatusDialog from './AdminUserStatusDialog.vue'
import { formatAccountTime } from '../../utils/account-status'
const auth = useAuthStore(),
  notices = useNotificationStore()
const keyword = ref(''),
  role = ref(''),
  status = ref(''),
  page = ref(1),
  error = ref(''),
  loading = ref(false),
  busy = ref(false)
const roles = ['社区成员', '创作者', '管理员']
const data = ref<PageData<AdminUser> | null>(null)
const statusTarget = ref<AdminUser | null>(null)
const drafts = reactive<Record<number, number>>({})
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getAdminUsers(
      {
        keyword: keyword.value.trim(),
        role: role.value === '' ? undefined : Number(role.value),
        status: status.value === '' ? undefined : Number(status.value),
        pageNo,
      },
      request.signal,
    )
    if (request.signal.aborted) return
    data.value = result
    page.value = pageNo
    result.list.forEach((user) => (drafts[user.id] = user.role))
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '用户列表加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function save(user: AdminUser) {
  const next = drafts[user.id]
  if (
    busy.value ||
    next === undefined ||
    next === user.role ||
    !window.confirm(`将用户 #${user.id}「${user.username}」的角色设为${roles[next]}？`)
  )
    return
  busy.value = true
  error.value = ''
  try {
    await updateUserRole(user.id, next, user.stateVersion)
    notices.show('用户角色已更新', 'success')
    await load(page.value)
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '角色更新失败'
    await load(page.value)
    error.value = message
  } finally {
    busy.value = false
  }
}
async function statusSaved() {
  statusTarget.value = null
  notices.show('用户状态已更新，操作记录已保存', 'success')
  await load(page.value)
}
void load()
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="work-stack">
    <h2>用户管理</h2>
    <form class="work-toolbar" @submit.prevent="load()">
      <input v-model="keyword" aria-label="搜索用户" maxlength="100" placeholder="用户名或手机" />
      <select v-model="role" aria-label="筛选角色">
        <option value="">全部角色</option>
        <option v-for="(label, value) in roles" :key="value" :value="String(value)">
          {{ label }}
        </option>
      </select>
      <select v-model="status" aria-label="账号状态"><option value="">全部状态</option><option value="1">正常</option><option value="0">封禁中</option></select>
      <BaseButton type="submit" variant="secondary" :disabled="busy || loading">
        搜索用户
      </BaseButton>
    </form>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在加载用户…</p>
    <div class="work-table-wrap">
      <table class="work-table">
        <thead>
          <tr>
            <th>用户</th>
            <th>资料</th>
            <th>角色</th><th>账号状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in data?.list ?? []" :key="user.id">
            <td>
              <RouterLink class="admin-user-identity" :to="userRoute(user.id)">
                <UserAvatar :src="user.avatarUrl" />
                <span>{{ user.username }}<small class="work-muted">#{{ user.id }}</small></span>
              </RouterLink>
            </td>
            <td>
              <p>{{ user.phone || '未提供手机' }}</p>
              <p>{{ user.signature || '暂无签名' }}</p>
              <small>{{ formatDate(user.createTime) }}</small>
            </td>
            <td>
              <select
                v-model.number="drafts[user.id]"
                :aria-label="`用户${user.id}角色`"
                :disabled="busy || loading || user.id === auth.session?.userId"
              >
                <option v-for="(label, value) in roles" :key="value" :value="value">
                  {{ label }}
                </option>
              </select>
            </td>
            <td>
              <strong :class="user.status === 0 ? 'work-error' : 'work-muted'">{{ user.status === 0 ? '封禁中' : '正常' }}</strong>
              <p v-if="user.status === 0" class="work-muted">{{ formatAccountTime(user.bannedUntil) }}</p>
              <p v-else-if="user.banExpired" class="work-muted">前次封禁已到期</p>
              <p v-if="user.banReason" class="account-reason">{{ user.banReason }}</p>
            </td>
            <td>
              <span v-if="user.id === auth.session?.userId" class="work-muted">当前账号</span>
              <BaseButton
                v-else
                size="small"
                :disabled="busy || loading || drafts[user.id] === user.role"
                @click="save(user)"
              >
                保存角色
              </BaseButton>
              <BaseButton v-if="user.id !== auth.session?.userId" size="small" variant="secondary"
                :disabled="busy || loading" @click="statusTarget = user">{{ user.status === 0 ? '解封' : '封禁' }}</BaseButton>
            </td>
          </tr>
          <tr v-if="!loading && !data?.list.length"><td colspan="5">暂无匹配用户。</td></tr>
        </tbody>
      </table>
    </div>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
    <AdminUserStatusDialog v-if="statusTarget" :user="statusTarget" @close="statusTarget = null" @saved="statusSaved" @refreshed="load(page)" />
  </section>
</template>

<style scoped>
.admin-user-identity { display: flex; width: fit-content; align-items: center; gap: .65rem; --user-avatar-size: 2.4rem; }
.admin-user-identity small { display: block; }
.account-reason { max-width: 20rem; white-space: normal; overflow-wrap: anywhere; margin-top: .4rem; }
</style>
