<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import {
  getRedisOverview,
  inspectGame,
  inspectResource,
  repairGame,
  repairResource,
  syncRedis,
  type RedisOverview,
  type GameConsistency,
  type ResourceConsistency,
  type Counters,
} from '../../api/operations'
import BaseButton from '../ui/BaseButton.vue'
const busy = defineModel<boolean>('busy', { required: true })
const overview = ref<RedisOverview | null>(null),
  game = ref<GameConsistency | null>(null),
  resource = ref<ResourceConsistency | null>(null)
const gameId = ref(''),
  resourceId = ref(''),
  error = ref(''),
  message = ref(''),
  loading = ref(false)
const fields: Array<[keyof Counters, string]> = [
  ['favoriteCount', '收藏'],
  ['viewCount', '浏览'],
  ['commentCount', '评论'],
  ['reviewCount', '评价'],
  ['downloadCount', '下载'],
  ['resourceCount', '资源'],
]
let controller: AbortController | undefined
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const result = await getRedisOverview(request.signal)
    if (!request.signal.aborted) overview.value = result
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : 'Redis 概览读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function perform(action: () => Promise<void>, success: string) {
  if (busy.value || loading.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
    message.value = success
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '一致性操作失败，请重新比对确认状态'
  } finally {
    busy.value = false
  }
}
function validId(value: string) {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0
}
function checkGame() {
  if (!validId(gameId.value)) return
  const id = Number(gameId.value)
  game.value = null
  void perform(async () => {
    game.value = await inspectGame(id)
  }, '游戏比对已完成。')
}
function checkResource() {
  if (!validId(resourceId.value)) return
  const id = Number(resourceId.value)
  resource.value = null
  void perform(async () => {
    resource.value = await inspectResource(id)
  }, '资源比对已完成。')
}
function fixGame() {
  const id = game.value?.gameId
  if (
    !id ||
    !window.confirm(
      `确认修复游戏 #${id} 的 Redis 与数据库冗余计数？将重新统计收藏、评论、评价、资源数量，并同步计数。`,
    )
  )
    return
  void perform(async () => {
    game.value = await repairGame(id)
    overview.value = await getRedisOverview()
  }, `游戏 #${id} 修复请求已完成。`)
}
function fixResource() {
  const id = resource.value?.resourceId
  if (!id || !window.confirm(`确认按 Redis 与数据库较大值修复资源 #${id} 的下载计数？`)) return
  void perform(async () => {
    resource.value = await repairResource(id)
    overview.value = await getRedisOverview()
  }, `资源 #${id} 修复请求已完成。`)
}
function sync() {
  if (!window.confirm('确认立即消费一批游戏与资源下载统计积压，并更新数据库冗余计数？')) return
  void perform(async () => {
    overview.value = await syncRedis()
    game.value = null
    resource.value = null
  }, '本批统计同步已完成，可刷新概览继续观察积压。')
}
void load()
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <section class="work-stack">
    <div class="work-toolbar">
      <h2>Redis 与数据库一致性</h2>
      <BaseButton variant="secondary" :disabled="busy || loading" @click="load">
        刷新 Redis 概览
      </BaseButton>
      <button class="work-danger" :disabled="busy || loading || !overview" @click="sync">
        同步一批计数积压
      </button>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="message" class="work-success" role="status">{{ message }}</p>
    <p v-if="loading" role="status">正在读取 Redis 概览…</p>
    <article v-if="overview" class="work-card">
      <h3>待同步 {{ overview.totalPending }} 项</h3>
      <p class="work-muted">{{ overview.backlogDetected ? '检测到积压' : '当前未检测到积压' }}</p>
      <div class="work-table-wrap">
        <table class="work-table">
          <thead>
            <tr>
              <th>待处理任务</th>
              <th>数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(count, key) in overview.pendingCounts" :key="key">
              <td>{{ key === 'mysql:resource-count-outbox' ? '资源统计校准' : key }}</td>
              <td>{{ count }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <details>
        <summary>异常字面量键</summary>
        <p v-if="!Object.keys(overview.suspiciousLiteralKeys).length">未检测到异常字面量键。</p>
        <p v-for="(type, key) in overview.suspiciousLiteralKeys" :key="key">
          {{ key }} · {{ type }}
        </p>
      </details>
    </article>
    <div class="work-grid">
      <form class="work-card" @submit.prevent="checkGame">
        <h3>游戏计数比对</h3>
        <label>
          游戏编号
          <input v-model="gameId" required type="number" min="1" max="9007199254740991" />
        </label>
        <div class="work-toolbar">
          <BaseButton
            type="submit"
            variant="secondary"
            :disabled="busy || loading || !validId(gameId)"
          >
            比对游戏计数
          </BaseButton>
        </div>
      </form>
      <form class="work-card" @submit.prevent="checkResource">
        <h3>资源下载比对</h3>
        <label>
          资源编号
          <input v-model="resourceId" required type="number" min="1" max="9007199254740991" />
        </label>
        <div class="work-toolbar">
          <BaseButton
            type="submit"
            variant="secondary"
            :disabled="busy || loading || !validId(resourceId)"
          >
            比对资源计数
          </BaseButton>
        </div>
      </form>
    </div>
    <article v-if="game" class="work-card">
      <div class="work-toolbar">
        <h3>游戏 #{{ game.gameId }} · {{ game.consistent ? '计数一致' : '发现差异' }}</h3>
        <button class="work-danger" :disabled="busy || loading" @click="fixGame">
          修复此游戏计数
        </button>
      </div>
      <div class="work-table-wrap">
        <table class="work-table">
          <thead>
            <tr>
              <th>计数</th>
              <th>数据库</th>
              <th>Redis</th>
              <th>关系表实时统计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="[key, label] in fields" :key="key">
              <td>{{ label }}</td>
              <td>{{ game.database?.[key] ?? '—' }}</td>
              <td>{{ game.redis?.[key] ?? '缺失或非法' }}</td>
              <td>{{ game.relationTruth?.[key] ?? '不适用' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <ul>
        <li v-for="issue in game.issues" :key="issue">{{ issue }}</li>
      </ul>
    </article>
    <article v-if="resource" class="work-card">
      <div class="work-toolbar">
        <h3>
          资源 #{{ resource.resourceId }} · {{ resource.consistent ? '计数一致' : '发现差异' }}
        </h3>
        <button class="work-danger" :disabled="busy || loading" @click="fixResource">
          修复此资源计数
        </button>
      </div>
      <p>
        数据库 {{ resource.databaseCount ?? '—' }} · Redis {{ resource.redisCount ?? '缺失或非法' }}
      </p>
      <ul>
        <li v-for="issue in resource.issues" :key="issue">{{ issue }}</li>
      </ul>
    </article>
  </section>
</template>
