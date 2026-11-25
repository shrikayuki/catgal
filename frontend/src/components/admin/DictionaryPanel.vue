<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { deleteDictionary, getDictionaryEntry, listDictionary, saveDictionary } from '../../api/catalog'
import type { CatalogEntry, DictionaryKind } from '../../types/catalog'
import type { PageData } from '../../api/types'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'
import { useNotificationStore } from '../../stores/notifications'

const props = defineProps<{ kind: DictionaryKind }>()
const notices = useNotificationStore()
const keyword = ref('')
const name = ref('')
const editing = ref<CatalogEntry | null>(null)
const page = ref(1)
const data = ref<PageData<CatalogEntry> | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
let controller: AbortController | undefined

async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await listDictionary(props.kind, keyword.value.trim(), pageNo, request.signal)
    if (request.signal.aborted) return
    data.value = result
    page.value = pageNo
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

async function edit(entry: CatalogEntry | null) {
  if (entry === null) { editing.value = null; name.value = ''; return }
  saving.value = true
  error.value = ''
  try {
    const fresh = await getDictionaryEntry(props.kind, entry.id)
    editing.value = fresh
    name.value = fresh.name
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取资料失败' }
  finally { saving.value = false }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await saveDictionary(props.kind, editing.value, name.value.trim())
    edit(null)
    notices.show('资料已保存', 'success')
    await load(page.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败'
  } finally { saving.value = false }
}

async function remove(entry: CatalogEntry) {
  if (!window.confirm(`确认删除「${entry.name}」？被游戏引用的资料会由服务端拒绝删除。`)) return
  saving.value = true
  error.value = ''
  try {
    await deleteDictionary(props.kind, entry)
    if (editing.value?.id === entry.id) edit(null)
    notices.show('资料已删除', 'success')
    await load(page.value)
    if (data.value?.list.length === 0 && page.value > 1) await load(page.value - 1)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败' }
  finally { saving.value = false }
}

watch(() => props.kind, () => { edit(null); keyword.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="dictionary-panel">
    <form class="catalog-toolbar" @submit.prevent="load()">
      <label class="sr-only" for="dictionary-keyword">搜索资料</label>
      <input id="dictionary-keyword" v-model="keyword" maxlength="100" placeholder="按名称搜索" type="search" />
      <BaseButton type="submit" variant="secondary" :disabled="loading || saving">搜索 / 刷新</BaseButton>
    </form>
    <form class="catalog-card catalog-toolbar" @submit.prevent="save">
      <label for="dictionary-name">{{ editing ? `编辑 #${editing.id}` : '新增资料' }}</label>
      <input id="dictionary-name" v-model="name" required :maxlength="kind === 'tags' ? 50 : 100" placeholder="输入名称" :disabled="saving" />
      <BaseButton type="submit" :loading="saving">{{ editing ? '保存修改' : '创建' }}</BaseButton>
      <BaseButton v-if="editing" variant="ghost" :disabled="saving" @click="edit(null)">取消编辑</BaseButton>
    </form>
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在加载资料…</p>
    <div class="catalog-table-wrap" :aria-busy="loading">
      <table class="catalog-table">
        <thead><tr><th>ID</th><th>名称</th><th>修订号</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="entry in data?.list ?? []" :key="entry.id">
            <td>#{{ entry.id }}</td><td>{{ entry.name }}</td><td>{{ entry.revision }}</td>
            <td class="catalog-actions"><BaseButton size="small" variant="secondary" :disabled="saving" @click="edit(entry)">编辑</BaseButton><button class="catalog-danger" :disabled="saving" @click="remove(entry)">删除</button></td>
          </tr>
          <tr v-if="!loading && data?.list.length === 0"><td colspan="4">暂无资料，可以在上方创建。</td></tr>
        </tbody>
      </table>
    </div>
    <PaginationControl v-if="data && !saving" :current-page="page" :total-pages="data.pages" @change="load" />
  </section>
</template>
