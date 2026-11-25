<script setup lang="ts">
import { RouterView } from 'vue-router'
import SiteFooter from './components/layout/SiteFooter.vue'
import SiteHeader from './components/layout/SiteHeader.vue'
import NotificationHost from './components/feedback/NotificationHost.vue'
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <SiteHeader />
    <main id="main-content" class="app-main" tabindex="-1">
      <RouterView v-slot="{ Component, route }">
        <Transition name="page" mode="out-in">
          <component :is="Component" :key="`${String(route.name)}-${route.meta.requiresAuth ? auth.session?.userId ?? 'guest' : ''}`" />
        </Transition>
      </RouterView>
    </main>
    <SiteFooter />
  </div>
  <NotificationHost />
</template>
