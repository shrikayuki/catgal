<script setup lang="ts">
import { directoryRoute } from '../../utils/navigation'
import { onBeforeUnmount, ref, watch } from 'vue'
import { getGameCompanies, getGameTags } from '../../api/discovery'
import type { CatalogEntry } from '../../types/catalog'
const props = defineProps<{ gameId: number }>()
const companies = ref<CatalogEntry[]>([]),
  tags = ref<CatalogEntry[]>([]),
  error = ref('')
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  companies.value = []
  tags.value = []
  error.value = ''
  try {
    const result = await Promise.all([
      getGameCompanies(props.gameId, request.signal),
      getGameTags(props.gameId, request.signal),
    ])
    if (!request.signal.aborted) [companies.value, tags.value] = result
  } catch {
    if (!request.signal.aborted) error.value = '分类资料暂不可用'
  }
}
watch(
  () => props.gameId,
  () => void load(),
  { immediate: true },
)
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <div class="catalog-links">
    <RouterLink
      v-for="item in companies"
      :key="`company-${item.id}`"
      :to="directoryRoute('companies', item.id)"
    >
      会社 · {{ item.name }}
    </RouterLink>
    <RouterLink v-for="item in tags" :key="`tag-${item.id}`" :to="directoryRoute('tags', item.id)">
      # {{ item.name }}
    </RouterLink>
    <button v-if="error" type="button" @click="load">{{ error }}，点击重试</button>
  </div>
</template>
<style scoped>
.catalog-links {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 1rem;
}
.catalog-links a,
.catalog-links button {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  padding: 0.4rem 0.7rem;
  background: var(--color-surface);
  color: var(--color-brand-700);
  font-size: var(--text-xs);
  text-decoration: none;
  overflow-wrap: anywhere;
  cursor: pointer;
}
</style>
