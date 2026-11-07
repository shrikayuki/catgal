<script setup lang="ts">
import { ArrowLeft, Camera, KeyRound, RefreshCw, Save, Upload, UserRound } from 'lucide-vue-next'
import { onBeforeUnmount, reactive, ref } from 'vue'
import { getUserHome, updateAvatar, updateProfile } from '../api/users'
import BaseButton from '../components/ui/BaseButton.vue'
import BaseInput from '../components/ui/BaseInput.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
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
const formError = ref('')
const form = reactive({ username: '', signature: '', oldPassword: '', newPassword: '', confirmPassword: '' })
let controller: AbortController | null = null

async function loadProfile(): Promise<void> {
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    profile.value = await getUserHome<UserComment>({ tab: 1, pageNo: 1, pageSize: 1 }, controller.signal)
    form.username = profile.value.username
    form.signature = profile.value.signature ?? ''
  } catch (caught) {
    if (caught instanceof DOMException && caught.name === 'AbortError') return
    error.value = caught instanceof Error ? caught.message : '个人资料加载失败'
  } finally {
    loading.value = false
  }
}

function validateProfile(): string {
  const username = form.username.trim()
  if (!username) return '用户名不能为空'
  if (username.length > 17) return '用户名不能超过 17 个字符'
  if (form.signature.length > 107) return '个人签名不能超过 107 个字符'
  if (!form.signature.trim() && profile.value?.signature) {
    return '当前后端暂不支持清空签名，请修改为其他内容'
  }
  if (form.newPassword) {
    if (!form.oldPassword) return '修改密码时必须填写原密码'
    if (form.newPassword.length < 6) return '新密码至少需要 6 位'
    if (form.newPassword !== form.confirmPassword) return '两次输入的新密码不一致'
  }
  return ''
}

async function saveProfile(): Promise<void> {
  if (!auth.session || !profile.value) return
  formError.value = validateProfile()
  if (formError.value) return
  saving.value = true
  try {
    const username = form.username.trim()
    await updateProfile(auth.session.userId, {
      username,
      signature: form.signature.trim() || undefined,
      oldPassword: form.newPassword ? form.oldPassword : undefined,
      newPassword: form.newPassword || undefined,
    })
    profile.value.username = username
    profile.value.signature = form.signature.trim() || profile.value.signature
    auth.setSession({ ...auth.session, username })
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    notifications.show('个人资料已保存', 'success')
  } catch (caught) {
    formError.value = caught instanceof Error ? caught.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

function chooseAvatar(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  if (!file) return
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    notifications.show('头像仅支持 JPG、PNG、GIF 或 WEBP', 'warning')
    input.value = ''
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    notifications.show('头像大小不能超过 2 MB', 'warning')
    input.value = ''
    return
  }
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
  avatarFile.value = file
  avatarPreview.value = URL.createObjectURL(file)
}

async function uploadSelectedAvatar(): Promise<void> {
  if (!avatarFile.value || !profile.value) return
  avatarUploading.value = true
  try {
    const url = await updateAvatar(avatarFile.value)
    profile.value.avatarUrl = url
    avatarFile.value = null
    if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
    avatarPreview.value = ''
    notifications.show('头像已更新', 'success')
  } catch (caught) {
    notifications.show(caught instanceof Error ? caught.message : '头像上传失败', 'error')
  } finally {
    avatarUploading.value = false
  }
}

void loadProfile()
onBeforeUnmount(() => {
  controller?.abort()
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
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
          <p>PROFILE SETTINGS</p>
          <h1>整理你的社区档案</h1>
          <span>头像、签名和账号安全设置会直接保存到当前后端。</span>
        </header>

        <div class="settings-grid">
          <aside class="avatar-card">
            <div class="avatar-preview">
              <img v-if="avatarPreview || profile.avatarUrl" :src="avatarPreview || profile.avatarUrl || ''" alt="头像预览" />
              <UserRound v-else :size="48" aria-hidden="true" />
              <span><Camera :size="15" />头像</span>
            </div>
            <h2>{{ profile.username }}</h2>
            <p>支持 JPG、PNG、GIF、WEBP，最大 2 MB。</p>
            <label class="file-button">
              <Upload :size="17" />选择图片
              <input type="file" accept="image/jpeg,image/png,image/gif,image/webp" @change="chooseAvatar" />
            </label>
            <BaseButton v-if="avatarFile" size="small" :loading="avatarUploading" @click="uploadSelectedAvatar">上传新头像</BaseButton>
          </aside>

          <main class="settings-form-card">
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
                <div class="form-section-heading"><KeyRound :size="20" /><div><h2>修改密码</h2><p>不修改密码时保持下面三项为空。</p></div></div>
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
          </main>
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
.settings-heading > p { margin: 0 0 0.6rem; color: var(--color-accent-violet); font-size: var(--text-xs); font-weight: 800; letter-spacing: 0.16em; }
.settings-heading h1 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: var(--text-3xl); font-weight: 500; letter-spacing: -0.04em; }
.settings-heading span { display: block; margin-top: 0.8rem; color: var(--color-text-secondary); line-height: 1.7; }
.settings-grid { display: grid; grid-template-columns: 18rem minmax(0, 1fr); align-items: start; gap: var(--space-8); }
.avatar-card,
.settings-form-card { border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: var(--color-surface); box-shadow: var(--shadow-md); }
.avatar-card { display: grid; justify-items: center; padding: var(--space-8); text-align: center; }
.avatar-preview { position: relative; display: grid; width: 9rem; height: 9rem; place-items: center; overflow: visible; border-radius: 42% 58% 52% 48%; background: var(--color-brand-100); color: var(--color-brand-600); }
.avatar-preview img { width: 100%; height: 100%; border-radius: inherit; object-fit: cover; }
.avatar-preview > span { position: absolute; right: -0.65rem; bottom: 0.25rem; display: inline-flex; align-items: center; gap: 0.3rem; border-radius: var(--radius-pill); background: var(--color-brand-700); color: white; font-size: var(--text-xs); font-weight: 800; padding: 0.45rem 0.65rem; }
.avatar-card h2 { margin: var(--space-5) 0 0; color: var(--color-brand-900); }
.avatar-card p { margin: 0.55rem 0 var(--space-5); color: var(--color-text-muted); font-size: var(--text-xs); line-height: 1.65; }
.file-button { display: inline-flex; min-height: 2.55rem; align-items: center; gap: 0.45rem; border: 1px solid var(--color-border-strong); border-radius: var(--radius-pill); color: var(--color-brand-800); font-size: var(--text-sm); font-weight: 800; padding: 0.65rem 1rem; cursor: pointer; }
.file-button input { position: absolute; width: 1px; height: 1px; overflow: hidden; opacity: 0; }
.avatar-card :deep(.base-button) { margin-top: var(--space-3); }
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

@media (max-width: 539px) {
  .avatar-card { display: grid; grid-template-columns: 1fr; justify-items: center; text-align: center; }
  .avatar-preview { grid-row: auto; }
  .form-fields--password { grid-template-columns: 1fr; }
  .form-fields--password > :first-child { grid-column: auto; }
}
</style>
