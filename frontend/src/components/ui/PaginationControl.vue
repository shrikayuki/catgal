<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { computed } from 'vue'

const props = defineProps<{
  currentPage: number
  totalPages: number
}>()

const emit = defineEmits<{
  change: [page: number]
}>()

const visiblePages = computed(() => {
  const start = Math.max(1, Math.min(props.currentPage - 2, props.totalPages - 4))
  const end = Math.min(props.totalPages, start + 4)
  return Array.from({ length: Math.max(0, end - start + 1) }, (_, index) => start + index)
})

function goTo(page: number): void {
  if (page >= 1 && page <= props.totalPages && page !== props.currentPage) emit('change', page)
}
</script>

<template>
  <nav v-if="totalPages > 1" class="pagination" aria-label="分页导航">
    <button type="button" :disabled="currentPage <= 1" aria-label="上一页" @click="goTo(currentPage - 1)">
      <ChevronLeft :size="18" aria-hidden="true" />
    </button>
    <button
      v-for="page in visiblePages"
      :key="page"
      type="button"
      :class="{ 'pagination__page--active': page === currentPage }"
      :aria-current="page === currentPage ? 'page' : undefined"
      @click="goTo(page)"
    >
      {{ page }}
    </button>
    <button type="button" :disabled="currentPage >= totalPages" aria-label="下一页" @click="goTo(currentPage + 1)">
      <ChevronRight :size="18" aria-hidden="true" />
    </button>
  </nav>
</template>

<style scoped>
.pagination { display: flex; align-items: center; justify-content: center; gap: 0.45rem; margin-top: var(--space-10); }
button { display: grid; min-width: 2.55rem; height: 2.55rem; place-items: center; border: 1px solid var(--color-border); border-radius: 50%; background: var(--color-surface); color: var(--color-text-secondary); font-weight: 700; cursor: pointer; }
button:hover:not(:disabled) { border-color: var(--color-brand-400); background: var(--color-brand-50); color: var(--color-brand-800); }
button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
button:disabled { cursor: not-allowed; opacity: 0.4; }
.pagination__page--active { border-color: var(--color-brand-700); background: var(--color-brand-700); color: var(--color-white); }
</style>
