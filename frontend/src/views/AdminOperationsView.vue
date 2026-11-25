<script setup lang="ts">
import { ref } from 'vue'
import AdminWorkspace from '../components/admin/AdminWorkspace.vue'
import SearchOperationsPanel from '../components/admin/SearchOperationsPanel.vue'
import RedisOperationsPanel from '../components/admin/RedisOperationsPanel.vue'
import AdminRewardOperationsPanel from '../components/admin/AdminRewardOperationsPanel.vue'
const busy = ref(false)
</script>
<template>
  <AdminWorkspace>
    <header>
      <h1>搜索与计数维护</h1>
      <p class="work-muted">查看同步积压及计数差异。重建、重试和修复均需要逐项确认。</p>
    </header>
    <div class="work-stack">
      <p v-if="busy" class="work-success" role="status">正在执行维护请求，请等待返回后刷新结果。</p>
      <SearchOperationsPanel v-model:busy="busy" />
      <AdminRewardOperationsPanel v-model:busy="busy" />
      <RedisOperationsPanel v-model:busy="busy" />
    </div>
  </AdminWorkspace>
</template>
