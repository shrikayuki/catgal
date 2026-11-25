<script setup lang="ts">
import { CircleAlert, Inbox, LoaderCircle, WifiOff } from 'lucide-vue-next'
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    tone?: 'empty' | 'loading' | 'error' | 'offline'
    title: string
    description?: string
  }>(),
  {
    tone: 'empty',
    description: undefined,
  },
)

const icon = computed(() => {
  if (props.tone === 'loading') return LoaderCircle
  if (props.tone === 'error') return CircleAlert
  if (props.tone === 'offline') return WifiOff
  return Inbox
})
</script>

<template>
  <section class="state-panel" :aria-busy="tone === 'loading'">
    <component :is="icon" class="state-panel__icon" :class="{ 'state-panel__icon--spin': tone === 'loading' }" aria-hidden="true" />
    <div>
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="state-panel__action">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.state-panel {
  display: grid;
  min-height: 14rem;
  place-items: center;
  align-content: center;
  gap: var(--space-4);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-xl);
  background: var(--color-surface-soft);
  padding: var(--space-8);
  text-align: center;
}

.state-panel__icon {
  width: 2rem;
  height: 2rem;
  color: var(--color-brand-500);
}

.state-panel__icon--spin {
  animation: spin 800ms linear infinite;
}

h2 {
  margin: 0;
  color: var(--color-text-primary);
  font-size: var(--text-lg);
}

p {
  max-width: 34rem;
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  line-height: 1.7;
}

.state-panel__action {
  margin-top: var(--space-2);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
