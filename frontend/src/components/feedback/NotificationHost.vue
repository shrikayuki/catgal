<script setup lang="ts">
import { X } from 'lucide-vue-next'
import { useNotificationStore } from '../../stores/notifications'

const notifications = useNotificationStore()
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
        <span>{{ item.message }}</span>
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
  right: var(--space-5);
  bottom: var(--space-5);
  display: grid;
  width: min(24rem, calc(100vw - 2rem));
  gap: var(--space-3);
  pointer-events: none;
}

.notification {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  border: 1px solid var(--color-border);
  border-left: 4px solid var(--color-brand-500);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
  color: var(--color-text-primary);
  padding: 0.85rem 1rem;
  pointer-events: auto;
}

.notification--success { border-left-color: var(--color-success); }
.notification--warning { border-left-color: var(--color-warning); }
.notification--error { border-left-color: var(--color-danger); }

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

.notice-enter-active,
.notice-leave-active { transition: 180ms ease; }
.notice-enter-from,
.notice-leave-to { opacity: 0; transform: translateY(0.5rem); }
</style>
