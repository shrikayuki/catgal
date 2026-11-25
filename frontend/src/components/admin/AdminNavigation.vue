<script setup lang="ts">
import { computed, ref, watchPostEffect } from 'vue'
import { Activity, Archive, BookOpen, ClipboardList, Gauge, Gift, MessageCircle, Settings, Star, UsersRound } from 'lucide-vue-next'
import { useRoute } from 'vue-router'

const route = useRoute()
const nav = ref<HTMLElement | null>(null)
const activePath = computed(() => {
  const path = route.path.replace(/\/$/, '')
  return path === '/admin/console' ? '/admin/console/dashboard' : path
})
const groups = [
  { title: '总览', items: [
    { to: '/admin/console/dashboard', label: '仪表盘', icon: Gauge },
    { to: '/admin/console/audit', label: '操作记录', icon: ClipboardList },
  ] },
  { title: '社区与内容', items: [
    { to: '/admin/console/users', label: '用户管理', icon: UsersRound },
    { to: '/admin/catalog', label: '游戏与资料', icon: BookOpen },
    { to: '/admin/console/resources', label: '资源审核', icon: Archive },
    { to: '/admin/console/comments', label: '评论治理', icon: MessageCircle },
    { to: '/admin/console/reviews', label: '评价治理', icon: Star },
    { to: '/admin/console/prizes', label: '奖池管理', icon: Gift },
  ] },
  { title: '站点维护', items: [
    { to: '/admin/operations', label: '搜索与计数', icon: Settings },
    { to: '/admin/load-tests', label: '并发测试', icon: Activity },
  ] },
]

watchPostEffect(() => {
  const active = activePath.value
  const node = nav.value
  if (!node || node.scrollWidth <= node.clientWidth) return
  const current = [...node.querySelectorAll('a')].find(link => link.getAttribute('href') === active)
  if (!current) return
  const outer = node.getBoundingClientRect()
  const inner = current.getBoundingClientRect()
  if (inner.left < outer.left) node.scrollLeft += inner.left - outer.left - 8
  else if (inner.right > outer.right) node.scrollLeft += inner.right - outer.right + 8
})
</script>

<template>
  <nav ref="nav" class="admin-nav" aria-label="后台管理导航">
    <div v-for="group in groups" :key="group.title" class="admin-nav__group">
      <p>{{ group.title }}</p>
      <RouterLink v-for="item in group.items" :key="item.to" :to="item.to" :class="{ active: activePath === item.to }" :aria-current="activePath === item.to ? 'page' : undefined">
        <component :is="item.icon" :size="17" aria-hidden="true" />{{ item.label }}
      </RouterLink>
    </div>
  </nav>
</template>

<style scoped>
.admin-nav { display: grid; gap: .9rem; }
.admin-nav__group { display: grid; gap: .25rem; }
.admin-nav__group p { margin: 0 0 .1rem; padding-inline: .65rem; color: var(--color-text-muted); font-size: var(--text-xs); font-weight: 700; }
.admin-nav a { position: relative; display: flex; min-height: 2.5rem; align-items: center; gap: .6rem; padding: .45rem .65rem .45rem .8rem; border: 1px solid transparent; border-radius: var(--radius-sm); color: var(--color-text-secondary); text-decoration: none; font-size: var(--text-sm); white-space: nowrap; transition: background var(--motion-fast), border-color var(--motion-fast), color var(--motion-fast); }
.admin-nav a::before { position: absolute; inset-block: .65rem; left: .26rem; width: 2px; border-radius: var(--radius-pill); background: var(--color-brand-700); content: ''; opacity: 0; transform: scaleY(.4); transition: opacity var(--motion-fast), transform var(--motion-fast); }
.admin-nav a svg { flex: none; }
.admin-nav a:hover { background: var(--color-surface-soft); color: var(--color-brand-800); }
.admin-nav a.active { border-color: var(--color-brand-100); background: var(--color-brand-50); color: var(--color-brand-800); font-weight: 700; }
.admin-nav a.active::before { opacity: 1; transform: none; }
@media (max-width: 959px) {
  .admin-nav { display: flex; gap: .4rem; padding: .25rem .15rem; overflow-x: auto; scrollbar-width: thin; }
  .admin-nav__group { display: flex; flex: none; gap: .3rem; }
  .admin-nav__group p { display: none; }
}
</style>
