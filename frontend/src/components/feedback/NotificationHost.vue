<script setup lang="ts">
import { CircleAlert, CircleCheck, Info, TriangleAlert, X } from 'lucide-vue-next'
import { useNotificationStore } from '../../stores/notifications'

const notifications = useNotificationStore()
const icons = { info: Info, success: CircleCheck, warning: TriangleAlert, error: CircleAlert }
</script>

<template>
  <div class="notifications" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="notice">
      <div
        v-for="item in notifications.items"
        :key="item.id"
        class="notification"
        :class="`notification--${item.tone}`"
        role="status"
      >
        <span class="notification__icon"><component :is="icons[item.tone]" :size="19" aria-hidden="true" /></span>
        <span class="notification__message">{{ item.message }}</span>
        <button type="button" aria-label="关闭提示" @click="notifications.remove(item.id)">
          <X :size="17" aria-hidden="true" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.notifications {
  position: fixed;
  z-index: var(--z-toast);
  right: max(1rem, env(safe-area-inset-right));
  bottom: max(1rem, env(safe-area-inset-bottom));
  display: grid;
  width: min(24rem, calc(100vw - 2rem));
  gap: var(--space-3);
  pointer-events: none;
}

.notification {
  --notification-color: var(--color-brand-700);
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: .65rem;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--notification-color);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
  color: var(--color-text-primary);
  padding: .7rem .75rem;
  pointer-events: auto;
}

.notification--success { --notification-color: var(--color-success); }
.notification--warning { --notification-color: var(--color-warning); }
.notification--error { --notification-color: var(--color-danger); }
.notification__icon { display: grid; width: 2.15rem; height: 2.15rem; place-items: center; border-radius: var(--radius-sm); background: color-mix(in srgb, var(--notification-color) 12%, var(--color-surface)); color: var(--notification-color); }
.notification__message { min-width: 0; overflow-wrap: anywhere; font-size: var(--text-sm); }

button {
  display: grid;
  width: 2rem;
  height: 2rem;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
}

button:hover { background: var(--color-brand-50); }
button:focus-visible { outline: 3px solid var(--color-focus); }

@media (prefers-reduced-motion: no-preference) {
  .notice-enter-active, .notice-leave-active { transition: opacity 170ms ease, transform 220ms ease; }
  .notice-enter-from, .notice-leave-to { opacity: 0; transform: translateY(.5rem) scale(.985); }
}
</style>
