<script setup lang="ts">
import { ArrowRight, BookOpen, CheckCircle2, ChevronDown, LockKeyhole, UserRound } from 'lucide-vue-next'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, register } from '../api/users'
import BaseButton from '../components/ui/BaseButton.vue'
import BaseInput from '../components/ui/BaseInput.vue'
import { useAuthStore } from '../stores/auth'
import { useNotificationStore } from '../stores/notifications'
import { recentRegistration, rememberRegistration, takeRegistration } from '../services/onboarding'
import { internalReturnPath } from '../utils/navigation'

type AuthMode = 'login' | 'register'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
const mode = ref<AuthMode>(route.query.mode === 'register' ? 'register' : 'login')
const submitting = ref(false)
const submitError = ref('')
const registeredUsername = ref(recentRegistration())
const form = reactive({
  username: mode.value === 'login' ? registeredUsername.value : '',
  password: '',
  confirmPassword: '',
  phone: '',
  signature: '',
})

const title = computed(() => (mode.value === 'login' ? '欢迎回到物语档案馆' : '创建你的社区档案'))
const justRegistered = computed(() => mode.value === 'login' && !!registeredUsername.value && form.username.trim() === registeredUsername.value)
const description = computed(() =>
  mode.value === 'login'
    ? '登录后收藏作品、参与讨论，并管理自己的资源与评价。'
    : '注册后即可建立个人主页，保存喜欢的作品与社区足迹。',
)

watch(mode, (nextMode) => {
  submitError.value = ''
  void router.replace({ query: { ...route.query, mode: nextMode === 'register' ? 'register' : undefined } })
})

function validate(): string {
  const username = form.username.trim()
  if (!username) return '请输入用户名'
  if (mode.value === 'register' && (username.length < 3 || username.length > 17)) {
    return '用户名长度需要在 3–17 个字符之间'
  }
  if (!form.password) return '请输入密码'
  if (mode.value === 'register' && form.password.length < 6) return '密码至少需要 6 位'
  if (mode.value === 'register' && form.password !== form.confirmPassword) return '两次输入的密码不一致'
  if (form.signature.length > 107) return '个人签名不能超过 107 个字符'
  return ''
}

async function submit(): Promise<void> {
  if (submitting.value) return
  submitError.value = validate()
  if (submitError.value) return

  const username = form.username.trim()
  submitting.value = true
  try {
    if (mode.value === 'login') {
      const session = await login({ username, password: form.password })
      const returnPath = internalReturnPath(route.query.redirect)
      auth.setSession(session)
      if (takeRegistration(username)) {
        await router.replace({ name: 'member-welcome', query: { next: returnPath } })
        return
      }
      notifications.show(`欢迎回来，${session.username}`, 'success')
      await router.replace(returnPath ?? '/profile')
      return
    }

    await register({
      username,
      password: form.password,
      phone: form.phone.trim() || undefined,
      signature: form.signature.trim() || undefined,
    })
    rememberRegistration(username)
    registeredUsername.value = username
    form.password = ''
    form.confirmPassword = ''
    form.phone = ''
    form.signature = ''
    mode.value = 'login'
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : '请求失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-page__wash" aria-hidden="true">
      <span />
      <span />
      <span />
    </div>
    <div class="container auth-layout">
      <section class="auth-story">
        <h1>把喜欢的作品，<br />留在同一页故事里。</h1>
        <p class="auth-story__lead">记录评价、整理收藏与分享资源。这里保留作品，也保留玩家真实留下的声音。</p>
        <ul>
          <li><BookOpen aria-hidden="true" /><span><strong>作品档案</strong>从游戏资料到社区评价，统一整理。</span></li>
          <li><UserRound aria-hidden="true" /><span><strong>个人主页</strong>展示评论、收藏夹与发布资源。</span></li>
          <li><LockKeyhole aria-hidden="true" /><span><strong>留下足迹</strong>写下评价，与同好分享故事的余韵。</span></li>
        </ul>
      </section>

      <section class="auth-card" aria-labelledby="auth-title">
        <div class="auth-tabs" role="tablist" aria-label="账号操作">
          <button type="button" role="tab" :aria-selected="mode === 'login'" :class="{ active: mode === 'login' }" :disabled="submitting" @click="mode = 'login'">登录</button>
          <button type="button" role="tab" :aria-selected="mode === 'register'" :class="{ active: mode === 'register' }" :disabled="submitting" @click="mode = 'register'">注册</button>
        </div>

        <div class="auth-card__heading">
          <h2 id="auth-title">{{ title }}</h2>
          <span>{{ description }}</span>
        </div>

        <p v-if="justRegistered" class="auth-created" role="status"><CheckCircle2 :size="20" aria-hidden="true" /><span><strong>账号已创建</strong>登录后就可以开始探索社区。</span></p>
        <p v-else-if="mode === 'login' && route.query.notice === 'account-banned'" class="work-error" role="alert">账号已被封禁，请联系管理员。</p>
        <p v-else-if="mode === 'login' && route.query.notice === 'password-changed'" class="work-success" role="status">密码已修改，请使用新密码重新登录。</p>
        <form class="auth-form" novalidate @submit.prevent="submit">
          <BaseInput v-model="form.username" name="username" label="用户名" autocomplete="username" placeholder="3–17 个字符" :disabled="submitting" />
          <BaseInput v-model="form.password" name="password" type="password" label="密码" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" placeholder="至少 6 位" :disabled="submitting" />
          <template v-if="mode === 'register'">
            <BaseInput v-model="form.confirmPassword" name="confirm-password" type="password" label="确认密码" autocomplete="new-password" placeholder="再次输入密码" :disabled="submitting" />
            <details class="auth-optional">
              <summary>补充资料 <small>选填</small><ChevronDown :size="16" aria-hidden="true" /></summary>
              <div>
                <BaseInput v-model="form.phone" name="phone" label="手机号" autocomplete="tel" :disabled="submitting" />
                <label class="text-field">
                  <span>个人签名</span>
                  <textarea v-model="form.signature" rows="3" maxlength="107" placeholder="写下一句想对同好说的话" :disabled="submitting" />
                  <small>{{ form.signature.length }}/107</small>
                </label>
              </div>
            </details>
          </template>

          <p v-if="submitError" class="form-error" role="alert">{{ submitError }}</p>
          <BaseButton type="submit" size="large" :loading="submitting">
            {{ mode === 'login' ? '进入社区' : '创建账号' }}
            <ArrowRight v-if="!submitting" :size="18" aria-hidden="true" />
          </BaseButton>
        </form>

        <p class="auth-card__switch">
          {{ mode === 'login' ? '还没有账号？' : '已经有账号？' }}
          <button type="button" :disabled="submitting" @click="mode = mode === 'login' ? 'register' : 'login'">
            {{ mode === 'login' ? '立即注册' : '返回登录' }}
          </button>
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.auth-page { position: relative; min-height: calc(100vh - 4.75rem); overflow: hidden; background: linear-gradient(135deg, #edf5fa 0%, #f8fafd 48%, #f8eff5 100%); padding: clamp(3rem, 7vw, 6rem) 0; }
.auth-page__wash { position: absolute; inset: 0; pointer-events: none; }
.auth-page__wash span { position: absolute; border: 1px solid rgb(101 168 209 / 16%); border-radius: 42% 58% 65% 35%; background: rgb(255 255 255 / 28%); }
.auth-page__wash span:nth-child(1) { width: 28rem; height: 28rem; top: -12rem; right: -6rem; transform: rotate(18deg); }
.auth-page__wash span:nth-child(2) { width: 18rem; height: 18rem; bottom: -8rem; left: 8%; transform: rotate(-28deg); }
.auth-page__wash span:nth-child(3) { width: 8rem; height: 8rem; top: 20%; left: 46%; transform: rotate(34deg); }
.auth-layout { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) minmax(22rem, 31rem); align-items: center; gap: clamp(3rem, 8vw, 8rem); }
.auth-story { max-width: 42rem; }
.auth-created { display: flex; gap: .6rem; align-items: flex-start; margin: 0 0 1rem; padding: .75rem .9rem; border: 1px solid var(--color-brand-100); border-radius: var(--radius-md); background: var(--color-brand-50); color: var(--color-text-secondary); font-size: var(--text-sm); }
.auth-created svg { flex: none; margin-top: .12rem; color: var(--color-brand-700); }
.auth-created strong { display: block; color: var(--color-brand-800); }
.auth-optional { border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-soft); }
.auth-optional summary { display: flex; align-items: center; gap: .45rem; min-height: 2.75rem; padding: .65rem .85rem; color: var(--color-brand-800); font-size: var(--text-sm); font-weight: 700; cursor: pointer; list-style: none; }
.auth-optional summary::-webkit-details-marker { display: none; }
.auth-optional summary small { color: var(--color-text-muted); font-size: var(--text-xs); font-weight: 400; }
.auth-optional summary svg { margin-left: auto; }
.auth-optional[open] summary svg { transform: rotate(180deg); }
.auth-optional > div { display: grid; gap: .85rem; margin-top: .2rem; padding: 0 .85rem .85rem; }
.auth-story h1 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: clamp(2.7rem, 6vw, 5.3rem); font-weight: 500; letter-spacing: -0.06em; line-height: 1.12; }
.auth-story__lead { max-width: 37rem; margin: 1.5rem 0 0; color: var(--color-text-secondary); font-size: var(--text-lg); line-height: 1.9; }
.auth-story ul { display: grid; gap: var(--space-4); margin: var(--space-8) 0 0; padding: 0; list-style: none; }
.auth-story li { display: flex; align-items: center; gap: var(--space-4); color: var(--color-text-secondary); }
.auth-story li > svg { width: 2.8rem; height: 2.8rem; flex: 0 0 auto; border-radius: 50%; background: rgb(255 255 255 / 76%); color: var(--color-brand-600); padding: 0.75rem; box-shadow: var(--shadow-sm); }
.auth-story strong { display: block; margin-bottom: 0.15rem; color: var(--color-text-primary); }
.auth-card { border: 1px solid rgb(203 217 226 / 78%); border-radius: 2rem; background: rgb(255 255 255 / 90%); box-shadow: 0 30px 80px rgb(44 72 105 / 16%); padding: clamp(1.5rem, 4vw, 2.5rem); backdrop-filter: blur(18px); }
.auth-tabs { display: grid; grid-template-columns: 1fr 1fr; border-radius: var(--radius-pill); background: var(--color-surface-soft); padding: 0.3rem; }
.auth-tabs button { min-height: 2.6rem; border: 0; border-radius: var(--radius-pill); background: transparent; color: var(--color-text-muted); font-weight: 800; cursor: pointer; }
.auth-tabs button.active { background: var(--color-surface); box-shadow: var(--shadow-sm); color: var(--color-brand-800); }
.auth-card__heading { margin: var(--space-8) 0 var(--space-6); }
.auth-card__heading h2 { margin: 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: var(--text-2xl); font-weight: 600; }
.auth-card__heading span { display: block; margin-top: 0.7rem; color: var(--color-text-secondary); line-height: 1.7; }
.auth-form { display: grid; gap: var(--space-5); }
.text-field { display: grid; gap: 0.45rem; color: var(--color-text-primary); font-size: var(--text-sm); font-weight: 700; }
.text-field textarea { width: 100%; resize: vertical; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text-primary); padding: 0.8rem 0.9rem; line-height: 1.6; }
.text-field textarea:focus { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); outline: 0; }
.text-field small { justify-self: end; color: var(--color-text-muted); font-weight: 400; }
.form-error { margin: -0.25rem 0 0; border-radius: var(--radius-md); background: #fff0f2; color: var(--color-danger); font-size: var(--text-sm); padding: 0.75rem 0.9rem; }
.auth-card__switch { margin: var(--space-6) 0 0; color: var(--color-text-muted); font-size: var(--text-sm); text-align: center; }
.auth-card__switch button { border: 0; background: transparent; color: var(--color-brand-700); font-weight: 800; cursor: pointer; }

@media (max-width: 899px) {
  .auth-layout { grid-template-columns: 1fr; max-width: 42rem; }
  .auth-story { text-align: center; }
  .auth-story__lead { margin-inline: auto; }
  .auth-story ul { display: none; }
}

@media (max-width: 519px) {
  .auth-page { padding-block: var(--space-8); }
  .auth-story h1 { font-size: 2.6rem; }
  .auth-card { border-radius: var(--radius-xl); padding: var(--space-5); }
}
</style>
