<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { addFavorite, removeFavorite } from '../../api/community'
import { getUserHome } from '../../api/users'
import type { UserFavoriteFolder } from '../../types/user'
import type { PageData } from '../../api/types'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'
const props = defineProps<{ gameId: number; gameName: string }>()
const emit = defineEmits<{ close: []; changed: [delta: number] }>()
const data = ref<PageData<UserFavoriteFolder> | null>(null),
  page = ref(1),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const confirmed = ref<Record<number, boolean>>({})
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  error.value = ''
  try {
    const result = await getUserHome<UserFavoriteFolder>(
      { tab: 3, pageNo, pageSize: 10 },
      request.signal,
    )
    if (!request.signal.aborted) {
      data.value = result.pageData
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '读取收藏夹失败'
  }
}
async function change(folder: UserFavoriteFolder, add: boolean) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const changed = add
      ? await addFavorite(folder.id, props.gameId)
      : await removeFavorite(folder.id, props.gameId)
    confirmed.value[folder.id] = add
    if (changed) {
      folder.gameCount = Math.max(0, (folder.gameCount ?? 0) + (add ? 1 : -1))
      emit('changed', add ? 1 : -1)
    }
    notice.value = add
      ? `已收藏到「${folder.name}」，收藏夹内容同步后可查看。`
      : `已从「${folder.name}」移出。`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '收藏操作失败'
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => controller?.abort())
void load()
</script>
<template>
  <ActionDialog :title="`收藏「${gameName}」`" @close="emit('close')">
    <p class="work-muted">可收藏到多个书架。选择收藏夹后分别操作。</p>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="work-success" role="status">{{ notice }}</p>
    <div class="work-stack">
      <article v-for="folder in data?.list ?? []" :key="folder.id" class="work-card">
        <h3>{{ folder.name }}</h3>
        <p class="work-muted">
          {{ folder.isPublic ? '公开' : '私有' }} · {{ folder.gameCount ?? 0 }} 部作品
        </p>
        <div class="work-toolbar">
          <BaseButton
            size="small"
            :disabled="busy || confirmed[folder.id] === true"
            @click="change(folder, true)"
          >
            {{ confirmed[folder.id] ? '已收藏到此夹' : '收藏到此夹' }}
          </BaseButton>
          <BaseButton
            variant="ghost"
            size="small"
            :disabled="busy || confirmed[folder.id] === false"
            @click="change(folder, false)"
          >
            从此夹移出
          </BaseButton>
        </div>
      </article>
    </div>
    <p v-if="data && !data.list.length" class="work-empty">先创建一个收藏夹，再回来整理作品。</p>
    <div class="work-toolbar">
      <BaseButton to="/favorites" variant="secondary" @click="emit('close')">
        管理 / 创建收藏夹
      </BaseButton>
      <BaseButton variant="ghost" :disabled="busy" @click="load(page)">刷新列表</BaseButton>
    </div>
    <PaginationControl v-if="data" :current-page="page" :total-pages="data.pages" @change="load" />
  </ActionDialog>
</template>
