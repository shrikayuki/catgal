<script setup lang="ts">
import { ShieldCheck } from 'lucide-vue-next'
import AdminNavigation from './AdminNavigation.vue'
</script>

<template>
  <div class="section admin-workspace">
    <div class="container admin-workspace__layout">
      <aside class="admin-workspace__nav">
        <div class="admin-workspace__name"><span><ShieldCheck :size="20" aria-hidden="true" /></span>社区管理</div>
        <AdminNavigation />
      </aside>
      <div class="workspace-page admin-workspace__content">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-workspace { min-height: 78vh; padding-block: clamp(1.15rem, 3vw, 2.6rem); background: linear-gradient(155deg, var(--color-brand-50), var(--color-bg) 24rem); }
.admin-workspace__layout { display: grid; grid-template-columns: 12.75rem minmax(0, 1fr); align-items: start; gap: clamp(1rem, 2vw, 2rem); }
.admin-workspace__nav { position: sticky; top: 6rem; max-height: calc(100dvh - 7rem); overflow: auto; min-width: 0; padding: .85rem; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-sm); }
.admin-workspace__name { display: flex; align-items: center; gap: .6rem; margin-bottom: .8rem; padding: .25rem .4rem .7rem; border-bottom: 1px solid var(--color-border); color: var(--color-brand-900); font-weight: 700; }
.admin-workspace__name span { display: grid; width: 2.35rem; height: 2.35rem; place-items: center; border-radius: var(--radius-sm); background: var(--color-brand-100); color: var(--color-brand-800); }
.admin-workspace__content { min-width: 0; }
.admin-workspace__content > :slotted(header) { margin-bottom: 1.15rem; padding: .9rem 1.25rem; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: linear-gradient(110deg, var(--color-surface) 35%, var(--color-brand-50)); box-shadow: var(--shadow-sm); }
.admin-workspace__content :deep(form.work-toolbar) { padding: .8rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.admin-workspace__content :deep(.work-stack > form.work-toolbar) { margin-block: 0; }
@media (max-width: 959px) {
  .admin-workspace__layout { grid-template-columns: minmax(0, 1fr); gap: .85rem; }
  .admin-workspace__nav { position: static; max-height: none; padding: .5rem .6rem; overflow: visible; }
  .admin-workspace__name { margin-bottom: .35rem; padding: .25rem .4rem .45rem; font-size: var(--text-sm); }
  .admin-workspace__name span { width: 1.95rem; height: 1.95rem; }
}
</style>
