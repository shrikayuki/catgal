<script setup lang="ts">
defineProps<{
  eyebrow?: string
  title: string
  description?: string
}>()
</script>

<template>
  <div class="section-heading">
    <p v-if="eyebrow" class="section-heading__eyebrow">{{ eyebrow }}</p>
    <div class="section-heading__row">
      <div>
        <h2>{{ title }}</h2>
        <p v-if="description">{{ description }}</p>
      </div>
      <div v-if="$slots.action" class="section-heading__action">
        <slot name="action" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.section-heading {
  display: grid;
  gap: 0.45rem;
}

.section-heading__eyebrow {
  margin: 0;
  color: var(--color-accent-violet);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.section-heading__row {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--space-5);
}

h2 {
  margin: 0;
  color: var(--color-text-primary);
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  font-weight: 600;
  letter-spacing: -0.035em;
}

p {
  max-width: 42rem;
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  line-height: 1.75;
}

@media (max-width: 639px) {
  .section-heading__row {
    align-items: start;
    flex-direction: column;
  }

  h2 {
    font-size: var(--text-2xl);
  }
}
</style>
