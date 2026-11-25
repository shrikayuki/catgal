<script setup lang="ts">
import { Palette } from 'lucide-vue-next'
import { resolveTheme, useTheme } from '../../services/theme'

const { theme, selectTheme } = useTheme()

function changeTheme(event: Event): void {
  selectTheme(resolveTheme((event.target as HTMLSelectElement).value))
}
</script>

<template>
  <label class="theme-switcher">
    <Palette :size="16" aria-hidden="true" />
    <span class="sr-only">界面主题</span>
    <select :value="theme" @change="changeTheme">
      <option value="shinku">真红</option>
      <option value="mist">雾樱</option>
      <option value="classic">经典蓝白</option>
    </select>
  </label>
</template>

<style scoped>
.theme-switcher {
  display: inline-flex;
  min-height: 2.5rem;
  align-items: center;
  gap: 0.4rem;
  justify-self: end;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-brand-700);
  padding-inline: 0.7rem 0.4rem;
}
.theme-switcher svg { flex-shrink: 0; }
select {
  min-width: 0;
  min-height: 2.4rem;
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: var(--text-xs);
  cursor: pointer;
}
.theme-switcher:focus-within { outline: 3px solid var(--color-focus); outline-offset: 3px; }
select:focus { outline: none; }
option { background: var(--color-surface); color: var(--color-text-primary); }
@media (max-width: 359px) {
  .theme-switcher { gap: 0.2rem; padding-inline: 0.4rem 0.2rem; }
}
</style>
