<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, BookOpen, Flower2, MessageCircle, UserRound } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BaseButton from '../components/ui/BaseButton.vue'
import { useAuthStore } from '../stores/auth'
import { internalReturnPath } from '../utils/navigation'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const continueTo = computed(() => {
  const next = internalReturnPath(route.query.next)
  if (!next) return undefined
  const destination = router.resolve(next)
  return ['login', 'member-welcome', 'not-found'].includes(String(destination.name)) ? undefined : destination.fullPath
})

const choices = [
  { title: '浏览游戏库', detail: '按平台、语言或标签发现作品。', to: { name: 'games' }, icon: BookOpen },
  { title: '完善个人档案', detail: '从本地选一张头像，再写一句签名。', to: { name: 'profile-settings' }, icon: UserRound },
  { title: '看看社区', detail: '读读其他玩家对作品的感想。', to: { name: 'community' }, icon: MessageCircle },
]
</script>

<template>
  <section class="container section welcome-page" aria-labelledby="welcome-title">
    <header class="welcome-intro">
      <span class="welcome-intro__icon" aria-hidden="true"><Flower2 :size="33" :stroke-width="1.5" /></span>
      <h1 id="welcome-title">欢迎加入，{{ auth.session?.username || '新朋友' }}</h1>
      <p>从一部感兴趣的作品开始，或给自己的档案添一点个人风格。</p>
      <BaseButton v-if="continueTo" :to="continueTo">继续刚才的操作 <ArrowRight :size="17" aria-hidden="true" /></BaseButton>
    </header>
    <nav class="welcome-choices" aria-label="开始使用 Catgal">
      <RouterLink v-for="choice in choices" :key="choice.title" :to="choice.to" class="welcome-choice">
        <span class="welcome-choice__icon"><component :is="choice.icon" :size="23" aria-hidden="true" /></span>
        <span class="welcome-choice__copy"><strong>{{ choice.title }}</strong><span>{{ choice.detail }}</span></span>
        <ArrowRight :size="18" aria-hidden="true" />
      </RouterLink>
    </nav>
    <RouterLink class="welcome-skip" :to="{ name: 'home' }">先去首页</RouterLink>
  </section>
</template>

<style scoped>
.welcome-page { max-width: 1050px; }
.welcome-intro { position: relative; isolation: isolate; overflow: hidden; padding: clamp(1.4rem, 5vw, 3.2rem); border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: linear-gradient(112deg, var(--color-surface), var(--color-brand-50) 62%, var(--color-surface-soft)); }
.welcome-intro::after { position: absolute; z-index: -1; right: -7rem; bottom: -10rem; width: 28rem; height: 17rem; border: 1px solid var(--color-border-strong); border-radius: 50%; box-shadow: 0 0 0 .75rem color-mix(in srgb, var(--color-surface) 50%, transparent); transform: rotate(-26deg); content: ''; }
.welcome-intro__icon { display: grid; width: 3.8rem; height: 3.8rem; place-items: center; border: 1px solid var(--color-brand-300); border-radius: var(--radius-lg); background: var(--color-surface); color: var(--color-brand-700); }
.welcome-intro h1 { margin: .95rem 0 0; color: var(--color-brand-900); font-family: var(--font-display); font-size: var(--text-3xl); line-height: 1.35; overflow-wrap: anywhere; }
.welcome-intro p { margin: .6rem 0 0; color: var(--color-text-secondary); }
.welcome-intro :deep(.base-button) { margin-top: 1.1rem; }
.welcome-choices { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .9rem; margin-top: 1.25rem; }
.welcome-choice { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: .65rem; padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); color: var(--color-brand-700); box-shadow: var(--shadow-sm); text-decoration: none; transition: background var(--motion-fast), border-color var(--motion-fast), box-shadow var(--motion-fast), transform var(--motion-fast); }
.welcome-choice:is(:hover, :focus-visible) { border-color: var(--color-brand-300); background: var(--color-brand-50); box-shadow: var(--shadow-md); }
.welcome-choice__icon { display: grid; width: 2.8rem; height: 2.8rem; place-items: center; border-radius: var(--radius-md); background: var(--color-brand-100); }
.welcome-choice__copy { display: grid; gap: .22rem; }
.welcome-choice__copy strong { color: var(--color-brand-900); }
.welcome-choice__copy > span { color: var(--color-text-secondary); font-size: var(--text-sm); }
.welcome-skip { display: inline-flex; margin-top: 1.15rem; color: var(--color-text-secondary); font-size: var(--text-sm); }
@media (prefers-reduced-motion: no-preference) {
  .welcome-intro__icon { animation: welcome-arrive 360ms ease-out both; }
  .welcome-choice:is(:hover, :focus-visible) { transform: translateY(-2px); }
  .welcome-choice > svg { transition: transform var(--motion-fast); }
  .welcome-choice:is(:hover, :focus-visible) > svg { transform: translateX(3px); }
  @keyframes welcome-arrive { from { opacity: .2; transform: translateY(.45rem) rotate(-8deg); } to { opacity: 1; transform: none; } }
}
@media (max-width: 850px) { .welcome-choices { grid-template-columns: minmax(0, 1fr); } }
</style>
