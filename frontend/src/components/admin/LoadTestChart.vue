<script setup lang="ts">
import { computed } from 'vue'
import { chartPoints } from '../../utils/load-tests'
const props = defineProps<{ title: string; unit: string; values: number[] }>()
const points = computed(() => chartPoints(props.values))
const maximum = computed(() => Math.max(0, ...props.values).toFixed(1))
</script>
<template>
  <section class="work-card load-chart">
    <div class="load-chart__heading">
      <h2>
        {{ title }}
      </h2>
      <span>
        {{ maximum }} {{ unit }}
      </span>
    </div>
    <p v-if="!values.length" class="work-muted">
      等待采样
    </p>
    <svg v-else viewBox="0 0 640 180" role="img" :aria-label="`${title}，最高 ${maximum} ${unit}`">
      <title>
        {{ title }}
      </title>
      <path d="M24 20H616 M24 90H616 M24 160H616" class="grid" />
      <polyline :points="points" class="line" />
      <circle v-for="(value, index) in values" :key="index" :cx="24 + index * 592 / Math.max(1, values.length - 1)" :cy="160 - value * 140 / Math.max(1, ...values)" r="3.5">
        <title>
          第 {{ index }} 秒，{{ value.toFixed(1) }} {{ unit }}
        </title>
      </circle>
    </svg>
    <div class="load-chart__axis">
      <span>
        0 秒
      </span>
      <span>
        {{ Math.max(0, values.length - 1) }} 秒
      </span>
    </div>
  </section>
</template>
<style scoped>
.load-chart { min-width: 0; }
.load-chart__heading, .load-chart__axis { display: flex; justify-content: space-between; align-items: center; gap: .5rem; }
.load-chart__heading h2 { margin: 0; font-size: 1rem; }
.load-chart__heading span, .load-chart__axis { color: var(--color-text-secondary); font-size: .8rem; }
svg { display: block; width: 100%; margin: 1rem 0 .25rem; }
.grid { fill: none; stroke: var(--color-border); stroke-dasharray: 4 5; }
.line { fill: none; stroke: var(--color-brand-600); stroke-width: 3; stroke-linejoin: round; }
circle { fill: var(--color-brand-600); }
</style>
