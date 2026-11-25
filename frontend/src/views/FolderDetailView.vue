<script setup lang="ts">
import { gameRoute, readPublicId } from '../utils/navigation'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getFolderGames, removeFavorite, type FolderGame } from '../api/community'
import type { PageData } from '../api/types'
import BaseButton from '../components/ui/BaseButton.vue'
import CoverImage from '../components/content/CoverImage.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
import { useAuthStore } from '../stores/auth'
const route = useRoute(),
  auth = useAuthStore()
const id = computed(() => readPublicId(route.params.folderId))
const isOwner = computed(() => !!auth.session && readPublicId(route.query.owner) === auth.session.userId)
const data = ref<PageData<FolderGame> | null>(null),
  page = ref(1),
  error = ref(''),
  loading = ref(false),
  busy = ref(false)
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  data.value = null
  error.value = ''
  try {
    const result = await getFolderGames(id.value, pageNo, request.signal)
    if (!request.signal.aborted) {
      data.value = result
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '收藏夹读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function remove(gameId: number) {
  if (busy.value || !isOwner.value || !window.confirm('确认从这个收藏夹移除作品？')) return
  busy.value = true
  error.value = ''
  try {
    await removeFavorite(id.value, gameId)
    await load(page.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '移除失败'
  } finally {
    busy.value = false
  }
}
watch([id, () => auth.session?.token], () => void load(), { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <div class="container section workspace-page">
    <header>
      <h1>收藏的故事</h1>
      <BaseButton v-if="auth.isLoggedIn" to="/favorites" variant="ghost">管理我的收藏夹</BaseButton>
    </header>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取作品…</p>
    <div class="work-grid">
      <article v-for="game in data?.list ?? []" :key="game.gameId" class="work-card">
        <div style="height: 16rem; overflow: hidden; border-radius: 0.8rem">
          <CoverImage :src="game.gameCoverUrl" :alt="game.gameName" />
        </div>
        <h2>{{ game.gameName }}</h2>
        <div class="work-toolbar">
          <BaseButton :to="gameRoute(game.gameId)" size="small">作品详情</BaseButton>
          <button v-if="isOwner" class="work-danger" :disabled="busy" @click="remove(game.gameId)">
            从收藏夹移除
          </button>
        </div>
      </article>
    </div>
    <p v-if="data && !data.list.length" class="work-empty">
      这里还没有作品，遇到喜欢的故事时记得收藏。
    </p>
    <BaseButton v-if="!loading" variant="secondary" @click="load(page)">刷新收藏夹</BaseButton>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
  </div>
</template>
