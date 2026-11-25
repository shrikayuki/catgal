<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AdminWorkspace from '../components/admin/AdminWorkspace.vue'
import AdminDashboard from '../components/admin/AdminDashboard.vue'
import AdminUsersPanel from '../components/admin/AdminUsersPanel.vue'
import AdminUserAuditPanel from '../components/admin/AdminUserAuditPanel.vue'
import AdminModerationPanel from '../components/admin/AdminModerationPanel.vue'
import AdminPrizePanel from '../components/admin/AdminPrizePanel.vue'
const route = useRoute()
const section = computed(() => String(route.params.section || 'dashboard'))
</script>
<template>
  <AdminWorkspace>
    <header>
      <h1>管理后台</h1>
    </header>
    <div :key="section" class="admin-console__panel">
      <AdminDashboard v-if="section === 'dashboard'" />
      <AdminUsersPanel v-else-if="section === 'users'" />
      <AdminUserAuditPanel v-else-if="section === 'audit'" />
      <AdminPrizePanel v-else-if="section === 'prizes'" />
      <AdminModerationPanel v-else-if="section === 'resources' || section === 'comments' || section === 'reviews'" :kind="section" />
    </div>
  </AdminWorkspace>
</template>

<style scoped>
@media (prefers-reduced-motion: no-preference) {
  .admin-console__panel { animation: console-arrive 200ms ease-out both; }
  @keyframes console-arrive { from { opacity: .5; transform: translateY(.3rem); } to { opacity: 1; transform: none; } }
}
</style>
