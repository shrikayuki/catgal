import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { readAuthSession } from '../services/auth-session'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    requiresAuth?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { title: '发现' },
    },
    {
      path: '/games',
      name: 'games',
      component: () => import('../views/GameLibraryView.vue'),
      meta: { title: '游戏库' },
    },
    {
      path: '/games/:gameId(\\d+)',
      name: 'game-detail',
      component: () => import('../views/GameDetailView.vue'),
      meta: { title: '游戏详情' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/AuthView.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('../views/UserProfileView.vue'),
      meta: { title: '个人主页', requiresAuth: true },
    },
    {
      path: '/users/:userId(\\d+)',
      name: 'user-profile',
      component: () => import('../views/UserProfileView.vue'),
      meta: { title: '用户主页' },
    },
    {
      path: '/settings',
      name: 'profile-settings',
      component: () => import('../views/ProfileSettingsView.vue'),
      meta: { title: '编辑档案', requiresAuth: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面不存在' },
    },
  ],
})

router.beforeEach((to) => {
  const session = readAuthSession()
  if (to.meta.requiresAuth && !session) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && session) {
    return { name: 'profile' }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '资源社区'
  document.title = `${title} · Catgal`
})

export default router
