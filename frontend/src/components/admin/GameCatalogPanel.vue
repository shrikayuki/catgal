<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { getAdminGame, listAdminGames, saveAdminGame, saveGameRelations, setGameStatus } from '../../api/catalog'
import type { AdminGame, AdminGameDetail, CatalogEntry, GameCatalogInput, DictionaryKind, GameCoverUpdate } from '../../types/catalog'
import type { PageData } from '../../api/types'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'
import CatalogRelationPicker from './CatalogRelationPicker.vue'
import ExternalCatalogPanel from './ExternalCatalogPanel.vue'
import GameCoverEditor from './GameCoverEditor.vue'
import { useNotificationStore } from '../../stores/notifications'

const emit = defineEmits<{ dirty: [value: boolean] }>()
const notices = useNotificationStore()
const keyword = ref('')
const page = ref(1)
const data = ref<PageData<AdminGame> | null>(null)
const selected = ref<AdminGameDetail | null>(null)
const editorOpen = ref(false)
const editorTab = ref<'base' | 'external'>('base')
const tags = ref<CatalogEntry[]>([])
const companies = ref<CatalogEntry[]>([])
const form = reactive({ name: '', coverUrl: '', introduction: '', releaseDate: '' })
const baseline = ref('')
const coverDirty = ref(false)
const coverEditorKey = ref(0)
const busy = ref(false)
const loading = ref(false)
const error = ref('')
let listController: AbortController | undefined
const statusNames = ['草稿', '已发布', '已下架']

function payload(): GameCatalogInput {
  return { name: form.name.trim(), coverUrl: form.coverUrl.trim() || null, introduction: form.introduction || null,
    releaseDate: form.releaseDate || null, tagIds: tags.value.map(item => item.id), companyIds: companies.value.map(item => item.id) }
}
const metadataDirty = computed(() => editorOpen.value && JSON.stringify(payload()) !== baseline.value)
const dirty = computed(() => metadataDirty.value || coverDirty.value)
watch(dirty, value => emit('dirty', value))

async function load(pageNo = 1) {
  listController?.abort()
  const request = new AbortController()
  listController = request
  loading.value = true
  error.value = ''
  try {
    const result = await listAdminGames(keyword.value.trim(), pageNo, request.signal)
    if (request.signal.aborted) return
    data.value = result
    page.value = pageNo
  } catch (cause) { if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { if (!request.signal.aborted) loading.value = false }
}

function fill(detail: AdminGameDetail | null) {
  coverDirty.value = false
  coverEditorKey.value++
  selected.value = detail
  form.name = detail?.game.name ?? ''
  form.coverUrl = detail?.game.coverUrl ?? ''
  form.introduction = detail?.game.introduction ?? ''
  form.releaseDate = detail?.game.releaseDate ?? ''
  tags.value = detail?.tags ?? []
  companies.value = detail?.companies ?? []
  baseline.value = JSON.stringify(payload())
}

function coverUpdated(result: GameCoverUpdate) {
  if (!selected.value) return
  selected.value.game.coverFileId = result.fileId
  selected.value.game.coverUrl = result.url
  selected.value.game.revision = result.revision
  form.coverUrl = result.url ?? ''
  baseline.value = JSON.stringify(payload())
  notices.show(result.fileId ? '封面已上传' : '封面已移除', 'success')
  void load(page.value)
}

async function open(gameId: number | null) {
  busy.value = true
  error.value = ''
  try {
    fill(gameId === null ? null : await getAdminGame(gameId))
    editorTab.value = 'base'
    editorOpen.value = true
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取游戏失败' }
  finally { busy.value = false }
}

function close() {
  if (dirty.value && !window.confirm('放弃尚未保存的游戏资料？')) return
  editorOpen.value = false
  fill(null)
}

async function refreshEditor() {
  if (!selected.value) return
  if (dirty.value && !window.confirm('重新读取会放弃尚未保存的修改，继续吗？')) return
  busy.value = true
  error.value = ''
  try { fill(await getAdminGame(selected.value.game.id)) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '刷新失败' }
  finally { busy.value = false }
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    const body = payload()
    if (selected.value) body.revision = selected.value.game.revision
    fill(await saveAdminGame(selected.value?.game.id ?? null, body))
    notices.show('游戏资料已保存', 'success')
    await load(page.value)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { busy.value = false }
}

async function changeStatus(status: number) {
  if (!selected.value || dirty.value) return
  if (!window.confirm(`确认将「${selected.value.game.name}」设为${statusNames[status]}？`)) return
  busy.value = true
  error.value = ''
  try {
    await setGameStatus(selected.value.game.id, status)
    fill(await getAdminGame(selected.value.game.id))
    notices.show('发布状态已更新', 'success')
    await load(page.value)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '状态更新失败' }
  finally { busy.value = false }
}

async function saveRelations(kind: DictionaryKind) {
  if (!selected.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const entries = kind === 'tags' ? tags.value : companies.value
    const detail = await saveGameRelations(selected.value.game.id, kind, entries.map(item => item.id), selected.value.game.revision)
    const savedBaseline = JSON.parse(baseline.value) as GameCatalogInput
    selected.value = detail
    if (kind === 'tags') {
      tags.value = detail.tags
      savedBaseline.tagIds = detail.tags.map(item => item.id)
    } else {
      companies.value = detail.companies
      savedBaseline.companyIds = detail.companies.map(item => item.id)
    }
    baseline.value = JSON.stringify(savedBaseline)
    notices.show(`${kind === 'tags' ? '标签' : '会社'}关联已保存`, 'success')
    await load(page.value)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '关联保存失败，请重新读取确认修订号' }
  finally { busy.value = false }
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) { event.preventDefault(); event.returnValue = '' }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(() => !dirty.value || window.confirm('游戏资料尚未保存，确认离开？'))
onBeforeUnmount(() => { listController?.abort(); window.removeEventListener('beforeunload', beforeUnload); emit('dirty', false) })
void load()
</script>

<template>
  <section class="game-catalog-panel">
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <template v-if="!editorOpen">
      <form class="catalog-toolbar" @submit.prevent="load()"><label class="sr-only" for="admin-game-keyword">搜索游戏</label><input id="admin-game-keyword" v-model="keyword" type="search" maxlength="100" placeholder="搜索游戏名称" /><BaseButton type="submit" variant="secondary" :disabled="loading || busy">搜索 / 刷新</BaseButton><BaseButton :disabled="busy" @click="open(null)">＋ 新建游戏</BaseButton></form>
      <p v-if="loading" role="status">正在加载游戏目录…</p>
      <div class="catalog-table-wrap"><table class="catalog-table"><thead><tr><th>编号</th><th>作品</th><th>状态</th><th>修订号</th><th>维护</th></tr></thead><tbody>
        <tr v-for="game in data?.list ?? []" :key="game.id"><td>#{{ game.id }}</td><td><strong>{{ game.name }}</strong><small class="catalog-block">{{ game.releaseDate || '日期未收录' }}</small></td><td><span class="catalog-status" :class="`status-${game.status}`">{{ statusNames[game.status] || '未知状态' }}</span></td><td>{{ game.revision }}</td><td><BaseButton size="small" variant="secondary" :disabled="busy" @click="open(game.id)">编辑资料与来源</BaseButton></td></tr>
        <tr v-if="!loading && data?.list.length === 0"><td colspan="5">暂无匹配的游戏。</td></tr>
      </tbody></table></div>
      <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
    </template>
    <template v-else>
      <div class="catalog-toolbar editor-heading"><BaseButton variant="ghost" :disabled="busy" @click="close">← 返回目录</BaseButton><h2>{{ selected ? selected.game.name : '创建游戏草稿' }}</h2><span v-if="selected" class="catalog-status">{{ statusNames[selected.game.status] }} · r{{ selected.game.revision }}</span></div>
      <div class="catalog-tabs" aria-label="游戏维护内容"><button :class="{ active: editorTab === 'base' }" :disabled="busy" @click="editorTab = 'base'">基础资料</button><button :class="{ active: editorTab === 'external' }" :disabled="!selected || dirty || busy" title="保存基础资料后即可管理外部关联" @click="editorTab = 'external'">Bangumi / VNDB</button></div>
      <GameCoverEditor v-if="editorTab === 'base'" :key="coverEditorKey" :game="selected?.game ?? null" :disabled="busy || metadataDirty" @updated="coverUpdated" @dirty="coverDirty = $event" @busy="busy = $event" />
      <form v-if="editorTab === 'base'" class="catalog-card editor-form" @submit.prevent="save">
        <fieldset :disabled="busy || coverDirty">
          <label>游戏名称<input v-model="form.name" required maxlength="100" placeholder="输入作品名称" /></label>
          <label>发售日期<input v-model="form.releaseDate" type="date" min="1000-01-01" max="9999-12-31" /></label>
          <label>游戏简介<textarea v-model="form.introduction" maxlength="12000" rows="6" placeholder="补充作品的故事与特色" /></label>
          <div class="editor-grid"><CatalogRelationPicker v-model="companies" kind="companies" :disabled="busy" /><CatalogRelationPicker v-model="tags" kind="tags" :disabled="busy" /></div>
          <div v-if="selected" class="catalog-toolbar"><BaseButton variant="secondary" :disabled="busy" @click="saveRelations('companies')">单独保存会社关联</BaseButton><BaseButton variant="secondary" :disabled="busy" @click="saveRelations('tags')">单独保存标签关联</BaseButton><small class="catalog-hint">单独保存仅提交所选关联，其他编辑可继续保存。</small></div>
        </fieldset>
        <div class="catalog-toolbar"><BaseButton type="submit" :loading="busy" :disabled="coverDirty">{{ selected ? '保存资料与关联' : '创建草稿' }}</BaseButton><BaseButton v-if="selected" variant="ghost" :disabled="busy" @click="refreshEditor">重新读取</BaseButton><small class="catalog-hint">{{ dirty ? '有尚未保存的修改' : '资料已同步' }}</small></div>
        <div v-if="selected" class="catalog-toolbar status-controls"><span>发布管理</span><BaseButton v-if="selected.game.status !== 1" variant="secondary" :disabled="busy || dirty" @click="changeStatus(1)">发布游戏</BaseButton><BaseButton v-if="selected.game.status === 1" variant="secondary" :disabled="busy || dirty" @click="changeStatus(2)">下架游戏</BaseButton><BaseButton v-if="selected.game.status === 2" variant="ghost" :disabled="busy || dirty" @click="changeStatus(0)">转为草稿</BaseButton></div>
      </form>
      <ExternalCatalogPanel v-else-if="selected" :key="selected.game.id" :game-id="selected.game.id" :game-name="selected.game.name" @changed="refreshEditor" />
    </template>
  </section>
</template>
