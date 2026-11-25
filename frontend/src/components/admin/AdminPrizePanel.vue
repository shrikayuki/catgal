<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import {
  getAdminPrizes,
  saveAdminPrize,
  updatePrizeStatus,
  type AdminPrize,
  type AdminPrizeInput,
} from '../../api/admin'
import type { PageData } from '../../api/types'
import BaseButton from '../ui/BaseButton.vue'
import PaginationControl from '../ui/PaginationControl.vue'
import { useNotificationStore } from '../../stores/notifications'
import { parseResourceUrl } from '../../utils/resource-url'
const notices = useNotificationStore()
const keyword = ref(''),
  month = ref(''),
  status = ref(''),
  page = ref(1),
  error = ref(''),
  busy = ref(false),
  loading = ref(false)
const data = ref<PageData<AdminPrize> | null>(null)
const editing = ref<number | null>(null),
  editorOpen = ref(false),
  baseline = ref('')
const types = ['', '萌萌点', '道具', '实物', '谢谢参与']
const form = reactive<AdminPrizeInput>({
  prizeName: '',
  prizeImage: '',
  prizeType: 1,
  month: new Date().toISOString().slice(0, 7),
  weight: 1,
  totalStock: 0,
  remainStock: 0,
  status: 0,
})
const dirty = computed(() => editorOpen.value && JSON.stringify(form) !== baseline.value)
let controller: AbortController | undefined
async function load(pageNo = 1) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getAdminPrizes(
      {
        keyword: keyword.value.trim(),
        month: month.value || undefined,
        status: status.value === '' ? undefined : Number(status.value),
        pageNo,
      },
      request.signal,
    )
    if (!request.signal.aborted) {
      data.value = result
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '奖池加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
function open(prize: AdminPrize | null) {
  if (dirty.value && !window.confirm('放弃尚未保存的奖品修改？')) return
  editing.value = prize?.id ?? null
  form.prizeName = prize?.prizeName ?? ''
  form.prizeImage = prize?.prizeImage ?? ''
  form.prizeType = prize?.prizeType ?? 1
  const storedMonth = prize?.month
  form.month = storedMonth?.length === 6 ? `${storedMonth.slice(0, 4)}-${storedMonth.slice(4)}` : storedMonth ?? new Date().toISOString().slice(0, 7)
  form.revision = prize?.revision
  form.weight = prize?.weight ?? 1
  form.totalStock = prize?.totalStock ?? 0
  form.remainStock = prize?.remainStock ?? 0
  form.status = prize?.status ?? 0
  baseline.value = JSON.stringify(form)
  editorOpen.value = true
}
function close() {
  if (!dirty.value || window.confirm('放弃尚未保存的奖品修改？')) editorOpen.value = false
}
async function save() {
  if (busy.value) return
  error.value = ''
  if (form.prizeImage && !parseResourceUrl(form.prizeImage)) {
    error.value = '奖品图片须为有效的 HTTP 或 HTTPS 地址'
    return
  }
  if (form.totalStock > 0 && form.remainStock > form.totalStock) {
    error.value = '剩余库存不能超过总库存'
    return
  }
  if (
    !window.confirm(
      `确认${editing.value === null ? '创建' : '更新'}奖品「${form.prizeName}」及其库存、权重和启用状态？`,
    )
  )
    return
  busy.value = true
  try {
    await saveAdminPrize(editing.value, {
      ...form,
      prizeName: form.prizeName.trim(),
      prizeImage: form.prizeImage?.trim() || null,
      remainStock: form.totalStock === 0 ? 0 : form.remainStock,
    })
    editorOpen.value = false
    notices.show('奖品已保存', 'success')
    await load(page.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '奖品保存失败'
  } finally {
    busy.value = false
  }
}
async function toggle(prize: AdminPrize) {
  if (
    busy.value ||
    !window.confirm(
      `确认${prize.status === 1 ? '停用' : '启用'}奖品 #${prize.id}「${prize.prizeName}」？`,
    )
  )
    return
  busy.value = true
  error.value = ''
  try {
    await updatePrizeStatus(prize.id, prize.status === 1 ? 0 : 1, prize.revision)
    notices.show('奖品状态已更新', 'success')
    await load(page.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '状态更新失败'
  } finally {
    busy.value = false
  }
}
const mayLeave = () => !dirty.value || window.confirm('奖品修改尚未保存，确认离开？')
onBeforeRouteLeave(mayLeave)
onBeforeRouteUpdate(mayLeave)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  controller?.abort()
  window.removeEventListener('beforeunload', beforeUnload)
})
void load()
</script>
<template>
  <section class="work-stack">
    <h2>奖池管理</h2>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <template v-if="editorOpen">
      <form class="work-card work-form" @submit.prevent="save">
        <h3>{{ editing === null ? '创建奖品' : `编辑奖品 #${editing}` }}</h3>
        <fieldset class="work-form" :disabled="busy" style="border: 0; padding: 0">
          <label>
            奖品名称
            <input v-model="form.prizeName" required maxlength="100" />
          </label>
          <label>
            奖品图片
            <input v-model="form.prizeImage" type="url" maxlength="500" />
          </label>
          <div class="work-grid">
            <label>
              奖品类型
              <select v-model.number="form.prizeType">
                <option v-for="value in [1, 2, 3, 4]" :key="value" :value="value">
                  {{ types[value] }}
                </option>
              </select>
            </label>
            <label>
              奖池月份
              <input
                v-model="form.month"
                type="month"
                required
                :readonly="editing !== null"
                min="1000-01"
                max="9999-12"
              />
            </label>
            <label>
              权重
              <input v-model.number="form.weight" type="number" required min="0" max="2147483647" />
            </label>
            <label>
              总库存（0 为不限）
              <input
                v-model.number="form.totalStock"
                type="number"
                required
                min="0"
                max="2147483647"
              />
            </label>
            <label>
              剩余库存
              <input
                v-model.number="form.remainStock"
                type="number"
                required
                min="0"
                :max="form.totalStock || 0"
                :disabled="form.totalStock === 0"
              />
            </label>
            <label>
              奖品状态
              <select v-model.number="form.status">
                <option :value="0">停用</option>
                <option :value="1">启用</option>
              </select>
            </label>
          </div>
        </fieldset>
        <div class="work-toolbar">
          <BaseButton type="submit" :loading="busy">保存奖品</BaseButton>
          <BaseButton variant="ghost" :disabled="busy" @click="close">返回奖池列表</BaseButton>
        </div>
      </form>
    </template>
    <template v-else>
      <form class="work-toolbar" @submit.prevent="load()">
        <input v-model="keyword" aria-label="搜索奖品" maxlength="100" placeholder="奖品名称" />
        <input v-model="month" aria-label="筛选奖池月份" type="month" />
        <select v-model="status" aria-label="筛选奖品状态">
          <option value="">全部状态</option>
          <option value="1">启用</option>
          <option value="0">停用</option>
        </select>
        <BaseButton type="submit" variant="secondary" :disabled="busy || loading">
          筛选奖品
        </BaseButton>
        <BaseButton :disabled="busy" @click="open(null)">新增奖品</BaseButton>
      </form>
      <p v-if="loading" role="status">正在读取奖池…</p>
      <div class="work-table-wrap">
        <table class="work-table">
          <thead>
            <tr>
              <th>奖品</th>
              <th>月份 / 类型</th>
              <th>库存 / 权重</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="prize in data?.list ?? []" :key="prize.id">
              <td>{{ prize.prizeName }} #{{ prize.id }}</td>
              <td>{{ prize.month }} · {{ types[prize.prizeType] }}</td>
              <td>
                {{
                  prize.totalStock === 0 ? '库存不限' : `${prize.remainStock} / ${prize.totalStock}`
                }}
                · 权重 {{ prize.weight }}
              </td>
              <td>{{ prize.status === 1 ? '启用' : '停用' }}</td>
              <td>
                <div class="work-toolbar">
                  <BaseButton
                    size="small"
                    variant="secondary"
                    :disabled="busy"
                    @click="open(prize)"
                  >
                    编辑奖品
                  </BaseButton>
                  <button class="work-link" :disabled="busy || loading" @click="toggle(prize)">
                    {{ prize.status === 1 ? '停用奖品' : '启用奖品' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && !data?.list.length"><td colspan="5">暂无匹配奖品。</td></tr>
          </tbody>
        </table>
      </div>
      <PaginationControl
        v-if="data"
        :current-page="page"
        :total-pages="data.pages"
        @change="load"
      />
    </template>
  </section>
</template>
