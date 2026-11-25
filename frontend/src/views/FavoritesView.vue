<script setup lang="ts">
import { folderRoute } from '../utils/navigation'
import { onBeforeUnmount, reactive, ref } from 'vue'
import { getUserHome } from '../api/users'
import { createFolder, deleteFolder } from '../api/community'
import { useAuthStore } from '../stores/auth'
import type { UserFavoriteFolder } from '../types/user'
import type { PageData } from '../api/types'
import BaseButton from '../components/ui/BaseButton.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'
const data = ref<PageData<UserFavoriteFolder> | null>(null)
const auth = useAuthStore()
const page = ref(1),
  busy = ref(false),
  loading = ref(false),
  error = ref('')
const form = reactive({ name: '', description: '', isPublic: false })
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getUserHome<UserFavoriteFolder>(
      { tab: 3, pageNo, pageSize: 12 },
      request.signal,
    )
    if (!request.signal.aborted) {
      data.value = result.pageData
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '收藏夹加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function create() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await createFolder(form.name.trim(), form.description.trim(), form.isPublic)
    form.name = ''
    form.description = ''
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建失败'
  } finally {
    busy.value = false
  }
}
async function remove(id: number) {
  if (busy.value || !window.confirm('删除此收藏夹及其中的收藏关系？')) return
  busy.value = true
  error.value = ''
  try {
    await deleteFolder(id)
    await load(page.value)
    if (!data.value?.list.length && page.value > 1) await load(page.value - 1)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除失败'
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => controller?.abort())
void load()
</script>
<template>
  <div class="container section workspace-page">
    <header>
      <p class="work-muted">MY COLLECTION</p>
      <h1>我的收藏夹</h1>
      <p>把喜欢的作品整理成自己的书架。</p>
    </header>
    <form class="work-card work-form" @submit.prevent="create">
      <h2>创建收藏夹</h2>
      <label>
        名称
        <input v-model="form.name" required maxlength="50" />
      </label>
      <label>
        简介
        <textarea v-model="form.description" rows="2" maxlength="200" />
      </label>
      <label class="work-check">
        <input v-model="form.isPublic" type="checkbox" />
        公开展示这个收藏夹
      </label>
      <div><BaseButton type="submit" :loading="busy">创建收藏夹</BaseButton></div>
    </form>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在加载…</p>
    <div class="work-grid" style="margin-top: 1.5rem">
      <article v-for="folder in data?.list ?? []" :key="folder.id" class="work-card">
        <span class="work-badge">{{ folder.isPublic ? '公开' : '私有' }}</span>
        <h2>{{ folder.name }}</h2>
        <p class="work-muted">{{ folder.description || '暂未填写简介' }}</p>
        <p>{{ folder.gameCount ?? 0 }} 部作品</p>
        <div class="work-toolbar">
          <BaseButton
            :to="folderRoute(folder.id, auth.session?.userId)"
            size="small"
          >
            打开收藏夹
          </BaseButton>
          <button class="work-danger" :disabled="busy" @click="remove(folder.id)">删除</button>
        </div>
      </article>
    </div>
    <p v-if="!loading && !data?.list.length" class="work-empty">还没有收藏夹，在上方创建一个吧。</p>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
  </div>
</template>
