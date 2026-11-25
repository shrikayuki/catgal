import { createRouter, createWebHistory } from 'vue-router'
import { normalizePublicLocation } from '../utils/navigation'
import HomeView from '../views/HomeView.vue'
import { onAuthSessionChanged, readAuthSession } from '../services/auth-session'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    requiresAuth?: boolean
    requiresAdmin?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (to, from, savedPosition) => {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 88 }
    if (to.path === from.path) return false
    return { top: 0 }
  },
  routes: [
    {
      path: '/admin/load-tests',
      name: 'admin-load-tests',
      component: () => import('../views/AdminLoadTestsView.vue'),
      meta: { title: '并发测试', requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/ai',
      name: 'ai-chat',
      component: () => import('../views/AiChatView.vue'),
      meta: { title: '我的聊天伙伴', requiresAuth: true },
    },
    {
      path: '/admin/operations',
      name: 'admin-operations',
      component: () => import('../views/AdminOperationsView.vue'),
      meta: { title: '搜索与计数维护', requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/console/:section(dashboard|users|resources|comments|reviews|prizes|audit)?',
      name: 'admin-console',
      component: () => import('../views/AdminConsoleView.vue'),
      meta: { title: '社区管理', requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/rewards',
      name: 'rewards',
      component: () => import('../views/RewardsView.vue'),
      meta: { title: '签到与奖池' },
    },
    {
      path: '/resources/new',
      name: 'resource-publish',
      component: () => import('../views/ResourcePublishView.vue'),
      meta: { title: '发布资源', requiresAuth: true },
    },
    {
      path: '/resources/:resourceId/edit',
      name: 'resource-edit',
      component: () => import('../views/ResourcePublishView.vue'),
      meta: { title: '编辑资源', requiresAuth: true },
    },
    {
      path: '/catalog/:kind(companies|tags)/:entryId?',
      name: 'directory',
      component: () => import('../views/DirectoryView.vue'),
      meta: { title: '资料目录' },
    },
    {
      path: '/users/:userId/connections',
      name: 'connections',
      component: () => import('../views/ConnectionsView.vue'),
      meta: { title: '关注与粉丝' },
    },
    {
      path: '/community',
      name: 'community',
      component: () => import('../views/CommunityHubView.vue'),
      meta: { title: '作品社区' },
    },
    {
      path: '/favorites',
      name: 'favorites',
      component: () => import('../views/FavoritesView.vue'),
      meta: { title: '我的收藏夹', requiresAuth: true },
    },
    {
      path: '/folders/:folderId',
      name: 'folder-detail',
      component: () => import('../views/FolderDetailView.vue'),
      meta: { title: '收藏夹' },
    },
    {
      path: '/admin/catalog',
      name: 'admin-catalog',
      component: () => import('../views/AdminCatalogView.vue'),
      meta: { title: '资料管理', requiresAuth: true, requiresAdmin: true },
    },
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
      path: '/games/:gameId',
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
      path: '/welcome',
      name: 'member-welcome',
      component: () => import('../views/MemberWelcomeView.vue'),
      meta: { title: '欢迎加入', requiresAuth: true },
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('../views/UserProfileView.vue'),
      meta: { title: '个人主页', requiresAuth: true },
    },
    {
      path: '/users/:userId',
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
  const normalized = normalizePublicLocation(to)
  if (normalized === false) return { name: 'not-found', params: { pathMatch: ['unavailable'] }, replace: true }
  if (normalized) return normalized
  const session = readAuthSession()
  if (to.meta.requiresAuth && !session) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && session?.role !== 2) return { name: 'profile' }
  if (to.name === 'login' && session) {
    return { name: 'profile' }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '资源社区'
  document.title = `${title} · Catgal`
})

const stopAuthListener = onAuthSessionChanged((reason) => {
  const current = router.currentRoute.value
  const session = readAuthSession()
  if (!session && reason === 'signed-out') {
    void router.replace({ name: 'home' })
  } else if (!session && reason === 'password-changed') {
    void router.replace({ name: 'login', query: { notice: 'password-changed', redirect: current.meta.requiresAuth ? current.fullPath : undefined } })
  } else if (current.meta.requiresAuth && !session) {
    void router.replace({ name: 'login', query: { redirect: current.fullPath, ...(reason === 'account-banned' ? { notice: 'account-banned' } : {}) } })
  } else if (current.meta.requiresAdmin && session?.role !== 2) {
    void router.replace({ name: 'profile' })
  }
})
if (import.meta.hot) import.meta.hot.dispose(stopAuthListener)

export default router
