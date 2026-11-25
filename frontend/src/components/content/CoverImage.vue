<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ImageOff } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    src?: string | null
    alt: string
    eager?: boolean
  }>(),
  { src: null, eager: false },
)

const failed = ref(false)
const showImage = computed(() => Boolean(props.src) && !failed.value)
const fallbackLabel = computed(() => props.alt.trim().slice(0, 1).toUpperCase() || 'C')

watch(
  () => props.src,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <figure class="cover-image">
    <img
      v-if="showImage"
      :src="src ?? undefined"
      :alt="alt"
      :loading="eager ? 'eager' : 'lazy'"
      decoding="async"
      @error="failed = true"
    />
    <div v-else class="cover-image__fallback" role="img" :aria-label="`${alt}暂无封面`">
      <span>{{ fallbackLabel }}</span>
      <ImageOff :size="20" aria-hidden="true" />
    </div>
  </figure>
</template>

<style scoped>
.cover-image {
  width: 100%;
  height: 100%;
  margin: 0;
  overflow: hidden;
  background: var(--color-brand-50);
}

img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--motion-normal);
}

.cover-image__fallback {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  align-content: center;
  gap: var(--space-3);
  background:
    linear-gradient(145deg, rgb(255 255 255 / 65%), transparent),
    radial-gradient(circle at 70% 20%, rgb(217 167 195 / 45%), transparent 35%),
    var(--color-brand-100);
  color: var(--color-brand-700);
}

.cover-image__fallback span {
  font-family: var(--font-display);
  font-size: clamp(3rem, 9vw, 5.5rem);
  line-height: 1;
}
</style>
