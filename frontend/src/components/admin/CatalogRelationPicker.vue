<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { listDictionary } from '../../api/catalog'
import type { CatalogEntry, DictionaryKind } from '../../types/catalog'
import type { PageData } from '../../api/types'
import BaseButton from '../ui/BaseButton.vue'

const props = defineProps<{ kind: DictionaryKind; disabled?: boolean }>()
const selected = defineModel<CatalogEntry[]>({ required: true })
const keyword = ref('')
const page = ref(1)
const data = ref<PageData<CatalogEntry> | null>(null)
const error = ref('')
const loading = ref(false)
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
  } catch (cause) { if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { if (!request.signal.aborted) loading.value = false }
}

function toggle(entry: CatalogEntry) {
  if (props.disabled) return
  const exists = selected.value.some(item => item.id === entry.id)
  if (!exists && selected.value.length >= 50) return
  selected.value = exists ? selected.value.filter(item => item.id !== entry.id) : [...selected.value, entry]
}
onMounted(() => void load())
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <fieldset class="relation-picker" :disabled="disabled">
    <legend>{{ kind === 'tags' ? '标签' : '会社' }} · 已选 {{ selected.length }}/50</legend>
    <div class="relation-picked"><button v-for="entry in selected" :key="entry.id" type="button" @click="toggle(entry)">{{ entry.name }} ×</button><small v-if="!selected.length">尚未关联</small></div>
    <div class="catalog-toolbar">
      <input v-model="keyword" type="search" maxlength="100" :aria-label="kind === 'tags' ? '搜索标签' : '搜索会社'" placeholder="搜索已有资料" @keydown.enter.prevent="load()" />
      <BaseButton size="small" variant="secondary" :disabled="loading || disabled" @click="load()">搜索</BaseButton>
    </div>
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <div class="relation-options" :aria-busy="loading">
      <label v-for="entry in data?.list ?? []" :key="entry.id">
        <input type="checkbox" :checked="selected.some(item => item.id === entry.id)" :disabled="disabled || (selected.length >= 50 && !selected.some(item => item.id === entry.id))" @change="toggle(entry)" />
        {{ entry.name }} <small>#{{ entry.id }}</small>
      </label>
      <small v-if="!loading && data?.list.length === 0">未找到资料，请先在资料管理中创建。</small>
    </div>
    <div v-if="data && data.pages > 1" class="catalog-toolbar">
      <BaseButton size="small" variant="ghost" :disabled="page <= 1 || loading || disabled" @click="load(page - 1)">上一页</BaseButton>
      <small>{{ page }} / {{ data.pages }}</small>
      <BaseButton size="small" variant="ghost" :disabled="page >= data.pages || loading || disabled" @click="load(page + 1)">下一页</BaseButton>
    </div>
  </fieldset>
</template>
