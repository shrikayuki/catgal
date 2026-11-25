<script setup lang="ts">
import BaseButton from '../components/ui/BaseButton.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
</script>

<template>
  <div class="not-found section">
    <div class="container not-found__inner">
      <p class="not-found__code">404</p>
      <StatePanel title="这一页还没有写进故事里" description="链接可能已经失效，也可能是地址输入有误。">
        <BaseButton to="/">回到发现页</BaseButton>
      </StatePanel>
    </div>
  </div>
</template>

<style scoped>
.not-found { min-height: 68vh; }
.not-found__inner { max-width: 52rem; text-align: center; }
.not-found__code { margin: 0 0 -1.5rem; color: var(--color-brand-100); font-family: var(--font-display); font-size: clamp(6rem, 20vw, 13rem); font-weight: 700; line-height: 1; }
</style>
