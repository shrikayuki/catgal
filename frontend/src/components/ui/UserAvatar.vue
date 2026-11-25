<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { UserRound } from 'lucide-vue-next'
import { parseResourceUrl } from '../../utils/resource-url'

const props = defineProps<{ src?: string | null }>()
const failed = ref(false)
const imageUrl = computed(() => {
  const source = props.src?.trim()
  if (source && /^\/api\/files\/avatars\/[0-9a-f]{32}$/.test(source)) return source
  return parseResourceUrl(source)?.href
})
watch(imageUrl, () => { failed.value = false })
</script>

<template>
  <span class="user-avatar" aria-hidden="true">
    <img v-if="imageUrl && !failed" :key="imageUrl" :src="imageUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" @error="failed = true" />
    <UserRound v-else />
  </span>
</template>

<style scoped>
.user-avatar {
  display: grid;
  width: var(--user-avatar-size, var(--discussion-avatar-size, 2.75rem));
  height: var(--user-avatar-size, var(--discussion-avatar-size, 2.75rem));
  flex: none;
  place-items: center;
  overflow: hidden;
  border-radius: var(--user-avatar-radius, 50%);
  background: var(--color-brand-100);
  color: var(--color-brand-700);
}
img { width: 100%; height: 100%; object-fit: cover; }
svg { width: 46%; height: 46%; }
</style>
