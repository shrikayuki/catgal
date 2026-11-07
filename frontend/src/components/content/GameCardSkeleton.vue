<template>
  <div class="skeleton-card" aria-hidden="true">
    <div class="skeleton-card__cover shimmer" />
    <div class="skeleton-card__body">
      <div class="skeleton-card__line skeleton-card__line--title shimmer" />
      <div class="skeleton-card__line shimmer" />
      <div class="skeleton-card__meta">
        <span class="shimmer" />
        <span class="shimmer" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.skeleton-card {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.skeleton-card__cover { aspect-ratio: 3 / 4; }
.skeleton-card__body { display: grid; gap: 0.8rem; padding: 1rem; }
.skeleton-card__line { width: 68%; height: 0.7rem; border-radius: 999px; }
.skeleton-card__line--title { width: 88%; height: 1rem; }
.skeleton-card__meta { display: flex; gap: 0.5rem; }
.skeleton-card__meta span { width: 3.5rem; height: 1.4rem; border-radius: 999px; }

.shimmer {
  background: linear-gradient(100deg, var(--color-brand-50) 15%, var(--color-brand-100) 45%, var(--color-brand-50) 75%);
  background-size: 220% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}

@keyframes shimmer { to { background-position-x: -220%; } }

@media (prefers-reduced-motion: reduce) {
  .shimmer { animation: none; }
}
</style>
