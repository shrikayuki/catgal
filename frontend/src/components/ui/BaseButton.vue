<script setup lang="ts">
import { RouterLink, type RouteLocationRaw } from 'vue-router'

withDefaults(
  defineProps<{
    to?: RouteLocationRaw
    variant?: 'primary' | 'secondary' | 'ghost'
    size?: 'small' | 'medium' | 'large'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    loading?: boolean
  }>(),
  {
    variant: 'primary',
    size: 'medium',
    type: 'button',
    disabled: false,
    loading: false,
  },
)

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()
</script>

<template>
  <RouterLink
    v-if="to"
    :to="to"
    class="base-button"
    :class="[`base-button--${variant}`, `base-button--${size}`]"
  >
    <slot />
  </RouterLink>
  <button
    v-else
    class="base-button"
    :class="[`base-button--${variant}`, `base-button--${size}`]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading"
    @click="emit('click', $event)"
  >
    <span v-if="loading" class="base-button__spinner" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.base-button {
  display: inline-flex;
  min-height: 2.75rem;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  font-weight: 700;
  line-height: 1;
  text-decoration: none;
  cursor: pointer;
  transition:
    transform var(--motion-fast),
    background-color var(--motion-fast),
    border-color var(--motion-fast),
    box-shadow var(--motion-fast);
}

.base-button:hover:not(:disabled) {
  transform: translateY(-1px);
}

.base-button:focus-visible {
  outline: 3px solid var(--color-focus);
  outline-offset: 3px;
}

.base-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.base-button--small {
  min-height: 2.25rem;
  padding: 0.55rem 0.9rem;
  font-size: var(--text-sm);
}

.base-button--medium {
  padding: 0.75rem 1.2rem;
  font-size: var(--text-sm);
}

.base-button--large {
  min-height: 3.25rem;
  padding: 0.95rem 1.5rem;
  font-size: var(--text-base);
}

.base-button--primary {
  background: var(--color-brand-700);
  color: var(--color-white);
  box-shadow: 0 10px 22px rgb(23 59 108 / 18%);
}

.base-button--primary:hover:not(:disabled) {
  background: var(--color-brand-800);
  box-shadow: 0 12px 26px rgb(23 59 108 / 24%);
}

.base-button--secondary {
  border-color: var(--color-border-strong);
  background: var(--color-surface);
  color: var(--color-brand-800);
}

.base-button--secondary:hover:not(:disabled),
.base-button--ghost:hover:not(:disabled) {
  border-color: var(--color-brand-300);
  background: var(--color-brand-50);
}

.base-button--ghost {
  background: transparent;
  color: var(--color-text-secondary);
}

.base-button__spinner {
  width: 1rem;
  height: 1rem;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 700ms linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
