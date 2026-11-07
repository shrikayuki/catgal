<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { LogOut, Menu, Search, UserRound, X } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BaseButton from '../ui/BaseButton.vue'
import { useAuthStore } from '../../stores/auth'
import { useNotificationStore } from '../../stores/notifications'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
const isMenuOpen = ref(false)
const searchText = ref('')

const profileLabel = computed(() => auth.session?.username || '登录')

watch(
  () => route.fullPath,
  () => {
    isMenuOpen.value = false
  },
)

function submitSearch(): void {
  const name = searchText.value.trim()
  void router.push({ name: 'games', query: name ? { name } : {} })
}

async function signOut(): Promise<void> {
  auth.signOut()
  notifications.show('已安全退出登录', 'info')
  await router.push('/')
}
</script>

<template>
  <header class="site-header">
    <div class="container site-header__inner">
      <RouterLink class="brand" to="/" aria-label="Catgal 首页">
        <span class="brand__mark" aria-hidden="true">
          <span class="brand__ear brand__ear--left" />
          <span class="brand__ear brand__ear--right" />
          <span class="brand__spark">✦</span>
        </span>
        <span>
          <strong>CATGAL</strong>
          <small>物語アーカイブ</small>
        </span>
      </RouterLink>

      <nav class="desktop-nav" aria-label="主要导航">
        <RouterLink to="/">发现</RouterLink>
        <RouterLink to="/games">游戏库</RouterLink>
        <a href="#community-preview" @click.prevent>社区</a>
      </nav>

      <form class="header-search" role="search" @submit.prevent="submitSearch">
        <Search :size="17" aria-hidden="true" />
        <label class="sr-only" for="header-search">搜索游戏</label>
        <input id="header-search" v-model="searchText" type="search" placeholder="搜索游戏名称" />
      </form>

      <BaseButton :to="auth.isLoggedIn ? '/profile' : '/login'" variant="secondary" size="small">
        <UserRound :size="17" aria-hidden="true" />
        {{ profileLabel }}
      </BaseButton>

      <button v-if="auth.isLoggedIn" class="sign-out-button" type="button" aria-label="退出登录" title="退出登录" @click="signOut">
        <LogOut :size="17" aria-hidden="true" />
      </button>

      <button
        class="menu-toggle"
        type="button"
        :aria-expanded="isMenuOpen"
        aria-controls="mobile-menu"
        :aria-label="isMenuOpen ? '关闭导航菜单' : '打开导航菜单'"
        @click="isMenuOpen = !isMenuOpen"
      >
        <X v-if="isMenuOpen" aria-hidden="true" />
        <Menu v-else aria-hidden="true" />
      </button>
    </div>

    <div v-if="isMenuOpen" id="mobile-menu" class="mobile-menu">
      <div class="container mobile-menu__inner">
        <form class="mobile-search" role="search" @submit.prevent="submitSearch">
          <Search :size="18" aria-hidden="true" />
          <label class="sr-only" for="mobile-search">搜索游戏</label>
          <input id="mobile-search" v-model="searchText" type="search" placeholder="搜索游戏名称" />
        </form>
        <nav aria-label="移动端导航">
          <RouterLink to="/">发现</RouterLink>
          <RouterLink to="/games">游戏库</RouterLink>
          <RouterLink :to="auth.isLoggedIn ? '/profile' : '/login'">{{ profileLabel }}</RouterLink>
          <RouterLink v-if="auth.isLoggedIn" to="/settings">编辑档案</RouterLink>
          <button v-if="auth.isLoggedIn" type="button" @click="signOut"><LogOut :size="17" />退出登录</button>
        </nav>
      </div>
    </div>
  </header>
</template>

<style scoped>
.site-header {
  position: sticky;
  z-index: var(--z-header);
  top: 0;
  border-bottom: 1px solid rgb(205 218 229 / 75%);
  background: rgb(248 250 253 / 92%);
  backdrop-filter: blur(14px);
}

.site-header__inner {
  display: grid;
  min-height: 4.75rem;
  grid-template-columns: auto auto minmax(12rem, 1fr) auto auto;
  align-items: center;
  gap: var(--space-6);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 0.7rem;
  color: var(--color-brand-800);
  text-decoration: none;
}

.brand__mark {
  position: relative;
  display: grid;
  width: 2.55rem;
  height: 2.55rem;
  place-items: center;
  border-radius: 0.85rem 0.85rem 1rem;
  background: var(--color-brand-700);
  box-shadow: 0 8px 18px rgb(23 59 108 / 20%);
  color: var(--color-white);
}

.brand__ear {
  position: absolute;
  top: -0.22rem;
  width: 0;
  height: 0;
  border-right: 0.48rem solid transparent;
  border-bottom: 0.75rem solid var(--color-brand-700);
  border-left: 0.48rem solid transparent;
}

.brand__ear--left { left: 0.2rem; transform: rotate(-17deg); }
.brand__ear--right { right: 0.2rem; transform: rotate(17deg); }
.brand__spark { font-size: 1rem; }

.brand strong,
.brand small { display: block; }
.brand strong { font-size: var(--text-base); letter-spacing: 0.12em; }
.brand small { margin-top: 0.1rem; color: var(--color-text-muted); font-size: 0.62rem; letter-spacing: 0.1em; }

.desktop-nav { display: flex; align-items: center; gap: 0.25rem; }
.desktop-nav a {
  border-radius: var(--radius-pill);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  font-weight: 700;
  padding: 0.7rem 0.85rem;
  text-decoration: none;
}
.desktop-nav a:hover,
.desktop-nav a.router-link-active { background: var(--color-brand-50); color: var(--color-brand-800); }

.header-search,
.mobile-search {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.55rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-muted);
  padding: 0 0.9rem;
}

.header-search { justify-self: end; width: min(100%, 22rem); }
.header-search:focus-within,
.mobile-search:focus-within { border-color: var(--color-brand-500); box-shadow: 0 0 0 3px var(--color-focus); }
.header-search input,
.mobile-search input { width: 100%; min-width: 0; height: 2.65rem; border: 0; outline: 0; background: transparent; color: var(--color-text-primary); }

.menu-toggle {
  display: none;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  background: var(--color-surface);
  color: var(--color-brand-800);
}

.sign-out-button {
  display: grid;
  width: 2.4rem;
  height: 2.4rem;
  place-items: center;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
}
.sign-out-button:hover { border-color: var(--color-brand-300); background: var(--color-brand-50); color: var(--color-brand-800); }

.mobile-menu { border-top: 1px solid var(--color-border); background: var(--color-surface); }
.mobile-menu__inner { display: grid; gap: var(--space-4); padding-block: var(--space-4) var(--space-6); }
.mobile-menu nav { display: grid; }
.mobile-menu nav a { border-bottom: 1px solid var(--color-border); color: var(--color-text-primary); font-weight: 700; padding: 0.9rem 0.25rem; text-decoration: none; }
.mobile-menu nav button { display: flex; align-items: center; gap: 0.5rem; border: 0; border-bottom: 1px solid var(--color-border); background: transparent; color: var(--color-danger); font-weight: 700; padding: 0.9rem 0.25rem; cursor: pointer; }

@media (max-width: 959px) {
  .site-header__inner { grid-template-columns: auto 1fr auto; gap: var(--space-3); }
  .desktop-nav,
  .header-search,
  .site-header__inner > :deep(.base-button),
  .sign-out-button { display: none; }
  .menu-toggle { display: grid; justify-self: end; }
}

@media (max-width: 419px) {
  .brand small { display: none; }
}
</style>
