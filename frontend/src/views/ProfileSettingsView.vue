<script setup lang="ts">
import { ArrowLeft, Camera, ImageUp, KeyRound, RefreshCw, Save, Upload, UserRound } from 'lucide-vue-next'
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { getUserHome, updateAvatar, updateProfile } from '../api/users'
import BaseButton from '../components/ui/BaseButton.vue'
import BaseInput from '../components/ui/BaseInput.vue'
import UserAvatar from '../components/ui/UserAvatar.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import { clearAuthSessionIfCurrent, getAuthToken } from '../services/auth-session'
import type { UserComment, UserHome } from '../types/user'

const auth = useAuthStore()
const notifications = useNotificationStore()
const profile = ref<UserHome | null>(null)
const loading = ref(true)
const error = ref('')
const saving = ref(false)
const avatarUploading = ref(false)
const avatarFile = ref<File | null>(null)
const avatarPreview = ref('')
const draggingAvatar = ref(false)
const formError = ref('')
const form = reactive({ username: '', signature: '', oldPassword: '', newPassword: '', confirmPassword: '' })
let controller: AbortController | null = null

async function loadProfile(): Promise<void> {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getUserHome<UserComment>({ tab: 1, pageNo: 1, pageSize: 1 }, request.signal)
    if (request.signal.aborted) return
    profile.value = result
    form.username = result.username
    form.signature = result.signature ?? ''
  } catch (caught) {
    if (request.signal.aborted) return
    error.value = caught instanceof Error ? caught.message : '个人资料加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

function validateProfile(): string {
  const username = form.username.trim()
  if (!username) return '用户名不能为空'
  if (username.length > 17) return '用户名不能超过 17 个字符'
  if (form.signature.length > 107) return '个人签名不能超过 107 个字符'
  if (!form.signature.trim() && profile.value?.signature) {
    return '签名不能为空，请写下一句话再保存'
  }
  if (form.newPassword) {
    if (!form.oldPassword) return '修改密码时必须填写原密码'
    if (form.newPassword.length < 6) return '新密码至少需要 6 位'
    if (form.newPassword !== form.confirmPassword) return '两次输入的新密码不一致'
  }
  return ''
}

async function saveProfile(): Promise<void> {
  const session = auth.session
  const currentProfile = profile.value
  if (saving.value || !session || !currentProfile || currentProfile.userId !== session.userId || getAuthToken() !== session.token) return
  formError.value = validateProfile()
  if (formError.value) return
  const username = form.username.trim()
  const signature = form.signature.trim() || undefined
  const newPassword = form.newPassword || undefined
  saving.value = true
  try {
    await updateProfile(session.userId, {
      username,
      signature,
      oldPassword: newPassword ? form.oldPassword : undefined,
      newPassword,
    }, session.token)
    if (newPassword) {
      // 改密成功后服务端已撤销旧凭据。仅清理本次提交使用的会话，由路由引导重新登录。
      if (clearAuthSessionIfCurrent(session.token, 'password-changed')) resetPasswordFields()
      return
    }
    if (!auth.updateSessionUsername(session.token, username)) return
    currentProfile.username = username
    currentProfile.signature = signature ?? currentProfile.signature
    resetPasswordFields()
    notifications.show('个人资料已保存', 'success')
  } catch (caught) {
    if (getAuthToken() === session.token) {
      formError.value = caught instanceof Error ? caught.message : '保存失败，请稍后重试'
    }
  } finally {
    saving.value = false
  }
}

function resetPasswordFields(): void {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}

function clearAvatarSelection(): void {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarFile.value = null
  avatarPreview.value = ''
}

function stageAvatar(file: File | undefined): void {
  if (!file || avatarUploading.value) return
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    notifications.show('头像仅支持 JPG、PNG、GIF 或 WEBP', 'warning')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    notifications.show('头像大小不能超过 2 MB', 'warning')
    return
  }
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarFile.value = file
  avatarPreview.value = URL.createObjectURL(file)
}

function chooseAvatar(event: Event): void {
  const input = event.target as HTMLInputElement
  stageAvatar(input.files?.[0])
  input.value = ''
}

function leaveAvatarPicker(event: DragEvent): void {
  const picker = event.currentTarget as HTMLElement
  if (!(event.relatedTarget instanceof Node) || !picker.contains(event.relatedTarget)) draggingAvatar.value = false
}

function dropAvatar(event: DragEvent): void {
  draggingAvatar.value = false
  stageAvatar(event.dataTransfer?.files[0])
}

async function uploadSelectedAvatar(): Promise<void> {
  const current = profile.value
  const token = auth.session?.token
  if (avatarUploading.value || !avatarFile.value || !current || !token) return
  avatarUploading.value = true
  try {
    const url = await updateAvatar(avatarFile.value)
    if (!auth.updateSessionAvatar(token, url) || profile.value !== current) return
    current.avatarUrl = url
    clearAvatarSelection()
    notifications.show('头像已更新', 'success')
  } catch (caught) {
    if (getAuthToken() === token) notifications.show(caught instanceof Error ? caught.message : '头像上传失败', 'error')
  } finally {
    avatarUploading.value = false
  }
}

watch(() => auth.session?.token, (token) => {
  profile.value = null
  clearAvatarSelection()
  draggingAvatar.value = false
  formError.value = ''
  resetPasswordFields()
  if (token) void loadProfile()
  else controller?.abort()
}, { immediate: true })
onBeforeUnmount(() => {
  controller?.abort()
  clearAvatarSelection()
})
</script>

<template>
  <div class="settings-page section">
    <div class="container settings-container">
      <RouterLink class="back-link" to="/profile"><ArrowLeft :size="16" />返回个人主页</RouterLink>

      <StatePanel v-if="loading" tone="loading" title="正在读取个人资料" />
      <StatePanel v-else-if="error || !profile" tone="error" title="无法读取个人资料" :description="error">
        <BaseButton variant="secondary" @click="loadProfile"><RefreshCw :size="17" />重新加载</BaseButton>
      </StatePanel>

      <template v-else>
        <header class="settings-heading">
          <h1>整理你的社区档案</h1>
          <span>换一张头像，写一句签名，让大家更了解你。</span>
        </header>

        <div class="settings-grid">
          <aside class="avatar-card">
            <div class="avatar-preview">
              <img v-if="avatarPreview" :key="avatarPreview" :src="avatarPreview" alt="头像预览" />
              <UserAvatar v-else :src="profile.avatarUrl" />
              <span class="avatar-preview__label"><Camera :size="15" />头像</span>
            </div>
            <h2>{{ profile.username }}</h2>
            <p>支持 JPG、PNG、GIF、WEBP，最大 2 MB。</p>
            <label class="avatar-picker" :class="{ 'avatar-picker--dragging': draggingAvatar, 'avatar-picker--ready': avatarFile }"
              @dragover.prevent="draggingAvatar = !avatarUploading && Boolean($event.dataTransfer?.types.includes('Files'))"
              @dragleave="leaveAvatarPicker" @drop.prevent="dropAvatar">
              <span class="avatar-picker__icon"><ImageUp :size="21" aria-hidden="true" /></span>
              <span class="avatar-picker__label"><strong>{{ avatarFile ? '更换图片' : '从本机选择图片' }}</strong><small>{{ avatarFile?.name ?? '也可把图片拖到这里' }}</small></span>
              <input class="sr-only" type="file" aria-label="选择本地头像" :disabled="avatarUploading" accept="image/jpeg,image/png,image/gif,image/webp" @change="chooseAvatar" />
            </label>
            <div v-if="avatarFile" class="avatar-actions">
              <BaseButton size="small" :loading="avatarUploading" @click="uploadSelectedAvatar"><Upload v-if="!avatarUploading" :size="15" aria-hidden="true" />上传新头像</BaseButton>
              <BaseButton size="small" variant="ghost" :disabled="avatarUploading" @click="clearAvatarSelection">取消选择</BaseButton>
            </div>
          </aside>

          <div class="settings-form-card">
            <form novalidate @submit.prevent="saveProfile">
              <section>
                <div class="form-section-heading"><UserRound :size="20" /><div><h2>基础资料</h2><p>这些信息会显示在个人主页。</p></div></div>
                <div class="form-fields">
                  <BaseInput v-model="form.username" name="settings-username" label="用户名" autocomplete="username" :disabled="saving" />
                  <label class="text-field">
                    <span>个人签名</span>
                    <textarea v-model="form.signature" rows="4" maxlength="107" :disabled="saving" />
                    <small>{{ form.signature.length }}/107</small>
                  </label>
                </div>
              </section>

              <section class="password-section">
                <div class="form-section-heading"><KeyRound :size="20" /><div><h2>修改密码</h2><p>修改密码后，所有设备都需要重新登录。</p></div></div>
                <div class="form-fields form-fields--password">
                  <BaseInput v-model="form.oldPassword" name="old-password" type="password" label="原密码" autocomplete="current-password" :disabled="saving" />
                  <BaseInput v-model="form.newPassword" name="new-password" type="password" label="新密码" autocomplete="new-password" :disabled="saving" />
                  <BaseInput v-model="form.confirmPassword" name="confirm-new-password" type="password" label="确认新密码" autocomplete="new-password" :disabled="saving" />
                </div>
              </section>

              <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
              <div class="form-actions">
                <BaseButton type="submit" :loading="saving"><Save v-if="!saving" :size="17" />保存修改</BaseButton>
                <BaseButton to="/profile" variant="ghost">取消</BaseButton>
              </div>
            </form>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.settings-page { min-height: 75vh; background: linear-gradient(180deg, var(--color-brand-50), var(--color-bg) 18rem); }
.settings-container { max-width: 1080px; }
.back-link { display: inline-flex; align-items: center; gap: 0.4rem; margin-bottom: var(--space-6); color: var(--color-brand-700); font-size: var(--text-sm); font-weight: 800; text-decoration: none; }
.settings-heading { max-width: 45rem; margin-bottom: var(--space-10); }
.settings-heading h1 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: var(--text-3xl); font-weight: 500; letter-spacing: -0.04em; }
.settings-heading span { display: block; margin-top: 0.8rem; color: var(--color-text-secondary); line-height: 1.7; }
.settings-grid { display: grid; grid-template-columns: 18rem minmax(0, 1fr); align-items: start; gap: var(--space-8); }
.avatar-card,
.settings-form-card { border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: var(--color-surface); box-shadow: var(--shadow-md); }
.avatar-card { display: grid; min-width: 0; justify-items: center; padding: clamp(1.05rem, 3vw, 1.65rem); background: linear-gradient(165deg, var(--color-brand-50), var(--color-surface) 55%); text-align: center; }
.avatar-preview { position: relative; display: grid; width: 9rem; height: 9rem; place-items: center; overflow: visible; border-radius: 42% 58% 52% 48%; background: var(--color-brand-100); color: var(--color-brand-600); }
.avatar-preview img { width: 100%; height: 100%; border-radius: inherit; object-fit: cover; }
.avatar-preview { --user-avatar-size: 100%; --user-avatar-radius: 42% 58% 52% 48%; }
.avatar-preview__label { position: absolute; right: -0.65rem; bottom: 0.25rem; display: inline-flex; align-items: center; gap: 0.3rem; border-radius: var(--radius-pill); background: var(--color-brand-700); color: white; font-size: var(--text-xs); font-weight: 800; padding: 0.45rem 0.65rem; }
.avatar-card h2 { margin: var(--space-5) 0 0; color: var(--color-brand-900); }
.avatar-card p { margin: 0.55rem 0 var(--space-5); color: var(--color-text-muted); font-size: var(--text-xs); line-height: 1.65; }
.avatar-picker { position: relative; display: flex; width: 100%; min-width: 0; min-height: 4rem; align-items: center; gap: .65rem; padding: .65rem; border: 1px dashed var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-surface-soft); color: var(--color-brand-800); cursor: pointer; text-align: left; transition: background var(--motion-fast), border-color var(--motion-fast), box-shadow var(--motion-fast); }
.avatar-picker:hover, .avatar-picker--dragging, .avatar-picker--ready { border-color: var(--color-brand-500); background: var(--color-brand-50); }
.avatar-picker--dragging, .avatar-picker:focus-within { box-shadow: 0 0 0 3px var(--color-focus); }
.avatar-picker:has(input:disabled) { cursor: wait; }
.avatar-picker__icon { display: grid; width: 2.25rem; height: 2.25rem; flex: none; place-items: center; border-radius: var(--radius-sm); background: var(--color-brand-100); }
.avatar-picker__label { display: grid; min-width: 0; gap: .2rem; }
.avatar-picker__label strong { font-size: var(--text-sm); }
.avatar-picker__label small { overflow: hidden; color: var(--color-text-muted); font-size: var(--text-xs); text-overflow: ellipsis; white-space: nowrap; }
.avatar-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: .35rem; margin-top: .7rem; }
.settings-form-card { padding: clamp(1.5rem, 4vw, 2.5rem); }
.settings-form-card form { display: grid; gap: var(--space-10); }
.form-section-heading { display: flex; align-items: flex-start; gap: var(--space-3); margin-bottom: var(--space-6); color: var(--color-brand-600); }
.form-section-heading h2 { margin: 0; color: var(--color-text-primary); font-size: var(--text-lg); }
.form-section-heading p { margin: 0.3rem 0 0; color: var(--color-text-muted); font-size: var(--text-sm); }
.form-fields { display: grid; gap: var(--space-5); }
.form-fields--password { grid-template-columns: 1fr 1fr; }
.form-fields--password > :first-child { grid-column: 1 / -1; }
.password-section { border-top: 1px solid var(--color-border); padding-top: var(--space-8); }
.text-field { display: grid; gap: 0.45rem; color: var(--color-text-primary); font-size: var(--text-sm); font-weight: 700; }
.text-field textarea { resize: vertical; border: 1px solid var(--color-border); border-radius: var(--radius-md); color: var(--color-text-primary); padding: 0.8rem 0.9rem; line-height: 1.65; }
.text-field textarea:focus { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); outline: 0; }
.text-field small { justify-self: end; color: var(--color-text-muted); font-weight: 400; }
.form-error { margin: 0; border-radius: var(--radius-md); background: #fff0f2; color: var(--color-danger); font-size: var(--text-sm); padding: 0.8rem 1rem; }
.form-actions { display: flex; flex-wrap: wrap; gap: var(--space-3); }

@media (max-width: 799px) {
  .settings-grid { grid-template-columns: 1fr; }
  .avatar-card { grid-template-columns: auto 1fr; justify-items: start; column-gap: var(--space-6); text-align: left; }
  .avatar-preview { grid-row: 1 / 5; }
  .avatar-card h2 { margin-top: 0; }
}

@media (prefers-reduced-motion: no-preference) {
  .avatar-preview img { animation: preview-arrive 220ms ease-out; }
  @keyframes preview-arrive { from { opacity: .3; transform: scale(.95); } to { opacity: 1; transform: none; } }
}

@media (max-width: 539px) {
  .avatar-card { display: grid; grid-template-columns: 1fr; justify-items: center; text-align: center; }
  .avatar-preview { grid-row: auto; }
  .form-fields--password { grid-template-columns: 1fr; }
  .form-fields--password > :first-child { grid-column: auto; }
}
</style>
