<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Activity, ArrowDownToLine, Play, RefreshCw, Square } from 'lucide-vue-next'
import AdminWorkspace from '../components/admin/AdminWorkspace.vue'
import LoadTestChart from '../components/admin/LoadTestChart.vue'
import BaseButton from '../components/ui/BaseButton.vue'
import { getLoadTestCatalog, getLoadTestHistory, getLoadTest, startLoadTest, stopLoadTest,
  type LoadTestCatalog, type LoadTestInput, type LoadTestRun } from '../api/load-tests'
import { comparableLoadTests, isLoadTestActive, loadTestInput } from '../utils/load-tests'
import { formatDate } from '../utils/format'
import { ApiError } from '../api/types'

const catalog = ref<LoadTestCatalog | null>(null), history = ref<LoadTestRun[]>([]), current = ref<LoadTestRun | null>(null)
const scenarioId = ref('games'), concurrency = ref(10), durationSeconds = ref(10), maxRequests = ref(1000)
const gameId = ref(''), keyword = ref(''), referenceId = ref(''), error = ref(''), busy = ref(false), loading = ref(false)
const pending = ref<LoadTestInput | null>(null)
const controller = new AbortController()
let disposed = false, generation = 0, timer: ReturnType<typeof setTimeout> | undefined
const selectedScenario = computed(() => catalog.value?.scenarios.find(item => item.id === scenarioId.value))
const active = computed(() => !!current.value && isLoadTestActive(current.value.state))
const formLocked = computed(() => busy.value || !!pending.value || !!catalog.value?.activeJobId)
const reference = computed(() => history.value.find(item => item.id === referenceId.value))
const compared = computed(() => [current.value, reference.value].filter((item): item is LoadTestRun => !!item))
const labels: Record<string, string> = { PREPARING: '预检中', RUNNING: '测试进行中', STOPPING: '等待在途请求结束', STOPPED: '已停止', COMPLETED: '已完成', FAILED: '预检或执行失败' }
const reasons: Record<string, string> = { USER_STOPPED: '管理员停止', REQUEST_LIMIT: '已达到请求上限', DURATION_LIMIT: '已达到发起时长', ERROR_RATE_LIMIT: '失败率达到保护阈值', LATENCY_LIMIT: 'P95 超过 3 秒保护阈值', SERVICE_STOPPED: '服务停止', BUSINESS_RESPONSE: '业务响应格式异常', REQUEST_UNAVAILABLE: '目标连接或响应异常', RUNNER_UNAVAILABLE: '执行器异常', EMPTY_RESPONSE: '目标响应为空', TIMEOUT: '请求超时', RESPONSE_TOO_LARGE: '响应超过 512 KiB 限制', INVALID_RESPONSE: '响应解析失败' }
const progress = computed(() => current.value ? Math.min(100, current.value.metrics.started / current.value.config.maxRequests * 100) : 0)
const statusStyle = computed(() => ({ background: `conic-gradient(var(--color-success) ${(current.value?.metrics.successRate ?? 0) * 3.6}deg, var(--color-danger) 0)` }))
function number(value: number | undefined, suffix = '') { return value === undefined || !Number.isFinite(value) ? '—' : value.toFixed(1) + suffix }
function message(cause: unknown) { return cause instanceof Error ? cause.message : '请求失败，请重新读取任务' }
function name(id: string) { return catalog.value?.scenarios.find(item => item.id === id)?.label ?? id }

async function reload() {
  if (loading.value || disposed) return
  loading.value = true
  try {
    const [settings, runs] = await Promise.all([getLoadTestCatalog(controller.signal), getLoadTestHistory(controller.signal)])
    if (disposed) return
    catalog.value = settings; history.value = runs
    const recovered = pending.value && runs.find(item => item.config.clientRequestId === pending.value?.clientRequestId)
    if (recovered) { pending.value = null; select(recovered) }
    else if (current.value && runs.some(item => item.id === current.value?.id)) select(runs.find(item => item.id === current.value?.id)!)
    else if (runs.length) select(runs.find(item => item.id === settings.activeJobId) ?? runs[0]!)
    else current.value = null
  } catch (cause) { if (!disposed) error.value = message(cause) }
  finally { if (!disposed) loading.value = false }
}
function select(run: LoadTestRun) {
  generation++
  clearTimeout(timer)
  current.value = run
  if (isLoadTestActive(run.state)) void poll(run.id, generation)
}
async function poll(id: string, version: number) {
  try {
    const result = await getLoadTest(id, controller.signal)
    if (disposed || version !== generation) return
    current.value = result
    if (!isLoadTestActive(result.state)) { await reload(); return }
  } catch (cause) {
    if (!disposed && version === generation) {
      error.value = message(cause)
      if (cause instanceof ApiError && [401, 403, 404].includes(cause.status)) {
        if (cause.status === 404) { current.value = null; await reload() }
        return
      }
    }
  }
  if (!disposed && version === generation) timer = setTimeout(() => void poll(id, version), 1500)
}
async function start(retry = false) {
  if (busy.value || disposed) return
  try {
    const input = retry && pending.value ? pending.value : loadTestInput({
      clientRequestId: crypto.randomUUID(), scenarioId: scenarioId.value, concurrency: concurrency.value,
      durationSeconds: durationSeconds.value, maxRequests: maxRequests.value, gameId: gameId.value, keyword: keyword.value,
    })
    if (!retry && !window.confirm(`确认对${name(input.scenarioId)}执行 ${input.concurrency} 并发测试？本次最多 ${input.maxRequests} 次，最长 ${input.durationSeconds} 秒，并占用当前服务资源。`)) return
    pending.value = input; busy.value = true; error.value = ''
    const run = await startLoadTest(input)
    if (disposed) return
    pending.value = null; select(run); await reload()
  } catch (cause) { if (!disposed) error.value = pending.value ? `${message(cause)}。创建结果可通过刷新历史核对，重试将沿用原请求标识。` : message(cause) }
  finally { if (!disposed) busy.value = false }
}
async function stop() {
  if (!current.value || busy.value) return
  busy.value = true; error.value = ''
  try { const run = await stopLoadTest(current.value.id); if (!disposed) select(run) }
  catch (cause) { if (!disposed) error.value = `${message(cause)}，请刷新任务确认停止状态` }
  finally { if (!disposed) busy.value = false }
}
function download() {
  if (!current.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(current.value, null, 2)], { type: 'application/json' }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `load-test-${current.value.id}.json`; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
void reload()
onBeforeUnmount(() => { disposed = true; generation++; clearTimeout(timer); controller.abort() })
</script>

<template>
  <AdminWorkspace>
    <header>
      <h1>
        并发测试
      </h1>
      <p class="work-muted">
        为重要只读接口做一次有边界的性能摸底，实时观察吞吐、延迟和失败情况。
      </p>
    </header>
    <div class="work-stack load-page">
    <p v-if="error" class="work-error" role="alert">
      {{ error }}
    </p>
    <p v-if="catalog && !catalog.enabled" class="work-error">
      站点暂未启用并发测试。
    </p>
    <div class="load-layout">
      <form class="work-card work-form load-config" @submit.prevent="start()">
        <h2>
          <Activity :size="20" />
          测试配置
        </h2>
        <label>
          测试场景
          <select v-model="scenarioId" :disabled="formLocked">
            <option v-for="item in catalog?.scenarios ?? []" :key="item.id" :value="item.id">
              {{ item.label }}
            </option>
          </select>
        </label>
        <code v-if="selectedScenario">
          GET {{ selectedScenario.path }}
        </code>
        <label v-if="selectedScenario?.requiresGame">
          游戏 ID
          <input v-model="gameId" inputmode="numeric" :disabled="formLocked" placeholder="已发布游戏的 ID" />
        </label>
        <label v-if="selectedScenario?.requiresKeyword">
          检索词
          <input v-model="keyword" maxlength="100" :disabled="formLocked" placeholder="例如 治愈" />
        </label>
        <label>
          并发数
          <input v-model.number="concurrency" type="number" min="1" max="50" :disabled="formLocked" />
        </label>
        <label>
          最长发起时长（秒）
          <input v-model.number="durationSeconds" type="number" min="1" max="60" :disabled="formLocked" />
        </label>
        <label>
          请求总上限
          <input v-model.number="maxRequests" type="number" min="1" max="5000" :disabled="formLocked" />
        </label>
        <p class="work-muted">
          时长或请求上限先到即结束。先进行一次响应预检，预检不计入图表。每个 Java 进程同时执行一个任务，离开页面后任务继续运行。
        </p>
        <BaseButton type="submit" :disabled="!catalog?.enabled || formLocked || loading">
          <Play :size="16" />
          开始测试
        </BaseButton>
        <BaseButton v-if="pending" variant="secondary" :disabled="busy" @click="start(true)">
          重试原创建请求
        </BaseButton>
        <BaseButton variant="ghost" :disabled="loading" @click="error = ''; reload()">
          <RefreshCw :size="16" />
          刷新历史与状态
        </BaseButton>
        <small class="work-muted">
          执行目标 {{ catalog?.target ?? '读取中' }}
          <br />
          最近 {{ catalog?.retainedRuns ?? 20 }} 次记录保存在当前进程，服务重启后清空。
        </small>
      </form>
      <section class="work-stack load-results" aria-label="测试结果">
        <div class="load-summary">
          <div>
            <p>
              {{ current ? name(current.config.scenarioId) : '准备就绪' }}
            </p>
            <h2 aria-live="polite">
              {{ current ? labels[current.state] : '选择一个场景开始' }}
            </h2>
            <p v-if="current">
              {{ current.config.concurrency }} 并发 · 已运行 {{ number(current.elapsedSeconds, ' 秒') }}
            </p>
          </div>
          <BaseButton v-if="active" variant="secondary" :disabled="busy || current?.state === 'STOPPING'" @click="stop">
            <Square :size="16" />
            停止测试
          </BaseButton>
          <BaseButton v-else :disabled="!current" variant="secondary" @click="download">
            <ArrowDownToLine :size="16" />
            导出结果
          </BaseButton>
          <progress :value="progress" max="100" aria-label="请求上限使用比例" />
        </div>
        <p v-if="current?.stopReason" class="work-muted">
          结束原因 {{ reasons[current.stopReason] ?? current.stopReason }}
        </p>
        <p v-if="current?.state === 'STOPPING'" class="work-muted" role="status">
          已停止发起新请求，等待在途请求结束，最多约 4 秒。
        </p>
        <div class="load-metrics">
          <article class="work-card">
            <span>
              平均吞吐
            </span>
            <strong>
              {{ number(current?.metrics.rps) }}
              <small>
                请求/秒
              </small>
            </strong>
          </article>
          <article class="work-card">
            <span>
              P95 响应
            </span>
            <strong>
              {{ current?.metrics.completed ? number(current.metrics.p95Ms, ' ms') : '—' }}
            </strong>
          </article>
          <article class="work-card">
            <span>
              平均响应
            </span>
            <strong>
              {{ current?.metrics.completed ? number(current.metrics.averageMs, ' ms') : '—' }}
            </strong>
          </article>
          <article class="work-card">
            <span>
              已完成 / 请求上限
            </span>
            <strong>
              {{ current?.metrics.completed ?? 0 }}
              <small>
                / {{ current?.config.maxRequests ?? maxRequests }}
              </small>
            </strong>
          </article>
        </div>
        <div class="load-charts">
          <LoadTestChart title="每秒完成请求" unit="次" :values="current && current.metrics.completed > 0 ? current.timeline.map(item => item.completed) : []" />
          <LoadTestChart title="逐秒 P95 响应" unit="ms" :values="current && current.metrics.completed > 0 ? current.timeline.map(item => item.p95Ms) : []" />
        </div>
        <section class="work-card load-outcomes">
          <div class="load-donut" :style="current?.metrics.completed ? statusStyle : {}">
            <span>
              {{ current?.metrics.completed ? number(current.metrics.successRate, '%') : '待采样' }}
            </span>
          </div>
          <div>
            <h2>
              请求结果
            </h2>
            <p>
              成功 {{ current?.metrics.succeeded ?? 0 }} · 失败 {{ current?.metrics.failed ?? 0 }} · 在途 {{ current?.metrics.inFlight ?? 0 }}
            </p>
            <p class="work-muted">
              P50 {{ number(current?.metrics.p50Ms, ' ms') }} · P99 {{ number(current?.metrics.p99Ms, ' ms') }} · 最大 {{ number(current?.metrics.maxMs, ' ms') }}
            </p>
            <p v-for="(count, kind) in current?.errors ?? {}" :key="kind" class="work-error">
              {{ reasons[kind] ?? kind }} × {{ count }}
            </p>
          </div>
        </section>
        <p class="work-muted">
          P95 表示 95% 的已采样请求耗时不超过该值。空样本秒以 0 占位，末秒可能未满，短时结果会有波动；压测生成器与被测服务共享机器资源。
        </p>
        <details v-if="current?.timeline.length" class="work-card">
          <summary>
            查看逐秒数据
          </summary>
          <div class="load-table">
            <table>
              <thead>
                <tr>
                  <th>
                    秒
                  </th>
                  <th>
                    完成
                  </th>
                  <th>
                    失败
                  </th>
                  <th>
                    P95 ms
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="point in current.timeline" :key="point.second">
                  <td>
                    {{ point.second }}
                  </td>
                  <td>
                    {{ point.completed }}
                  </td>
                  <td>
                    {{ point.failed }}
                  </td>
                  <td>
                    {{ number(point.p95Ms) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </section>
    </div>
    <section class="work-card work-stack">
      <div class="work-toolbar">
        <h2>
          最近测试与对比
        </h2>
        <label>
          选择对照记录
          <select v-model="referenceId">
            <option value="">
              请选择
            </option>
            <option v-for="run in history.filter(item => item.id !== current?.id && !isLoadTestActive(item.state))" :key="run.id" :value="run.id">
              {{ name(run.config.scenarioId) }} · {{ run.config.concurrency }} 并发 · {{ formatDate(run.createdAt) }}
            </option>
          </select>
        </label>
      </div>
      <p v-if="reference && current && !comparableLoadTests(current.config, reference.config)" class="work-muted">
        两次测试参数不同，结果仅供对照，请核对场景、游戏、检索词和规模。
      </p>
      <div v-if="reference && current" class="load-table">
        <table>
          <thead>
            <tr>
              <th>
                记录
              </th>
              <th>
                吞吐/秒
              </th>
              <th>
                成功率
              </th>
              <th>
                平均 ms
              </th>
              <th>
                P95 ms
              </th>
              <th>
                P99 ms
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in compared" :key="run.id">
              <td>
                {{ run.id === current.id ? '当前' : '对照' }}
              </td>
              <td>
                {{ number(run.metrics.rps) }}
              </td>
              <td>
                {{ number(run.metrics.successRate, '%') }}
              </td>
              <td>
                {{ number(run.metrics.averageMs) }}
              </td>
              <td>
                {{ number(run.metrics.p95Ms) }}
              </td>
              <td>
                {{ number(run.metrics.p99Ms) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="load-history">
        <button v-for="run in history" :key="run.id" type="button" :aria-pressed="current?.id === run.id" @click="select(run)">
          <strong>
            {{ name(run.config.scenarioId) }} · {{ run.config.concurrency }} 并发
          </strong>
          <span>
            {{ labels[run.state] }} · {{ formatDate(run.createdAt) }}
          </span>
          <small>
            {{ run.metrics.completed }} 次 · P95 {{ number(run.metrics.p95Ms, ' ms') }}
          </small>
        </button>
        <p v-if="!history.length" class="work-muted">
          当前进程还没有测试记录。
        </p>
      </div>
    </section>
    </div>
  </AdminWorkspace>
</template>

<style scoped>
.load-layout { display: grid; grid-template-columns: minmax(15rem, 19rem) minmax(0, 1fr); gap: 1.25rem; align-items: start; }
.load-config h2 { display: flex; align-items: center; gap: .5rem; }
.load-config code { font-size: .8rem; overflow-wrap: anywhere; color: var(--color-brand-600); }
.load-results { min-width: 0; }
.load-summary { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1rem; padding: 1.5rem; border-radius: var(--radius-lg); color: white; background: linear-gradient(120deg, var(--color-brand-800), var(--color-brand-600)); }
.load-summary p { margin: .3rem 0; font-size: .85rem; opacity: .9; }
.load-summary h2 { color: white; margin: .5rem 0; }
progress { width: 100%; height: .5rem; border: 0; border-radius: 99px; accent-color: var(--color-brand-300); }
.load-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .75rem; }
.load-metrics article { padding: 1rem; }
.load-metrics span { color: var(--color-text-secondary); font-size: .8rem; }
.load-metrics strong { display: block; font-size: clamp(1.1rem, 1.6vw, 1.65rem); color: var(--color-brand-800); margin-top: .8rem; }
.load-metrics small { font-size: .75rem; font-weight: 400; }
.load-charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
.load-outcomes { display: flex; gap: 1.5rem; align-items: center; flex-wrap: wrap; }
.load-donut { width: 6rem; height: 6rem; border-radius: 50%; background: var(--color-border); display: grid; place-items: center; flex-shrink: 0; }
.load-donut span { width: 4.5rem; height: 4.5rem; border-radius: 50%; background: white; display: grid; place-items: center; font-size: .9rem; font-weight: 700; }
.load-table { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: .85rem; white-space: nowrap; }
th, td { text-align: left; padding: .8rem; border-bottom: 1px solid var(--color-border); }
summary { cursor: pointer; }
.load-history { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr)); gap: .75rem; }
.load-history button { display: grid; gap: .5rem; text-align: left; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 1rem; background: var(--color-surface); color: var(--color-text-primary); cursor: pointer; }
.load-history button[aria-pressed='true'] { border-color: var(--color-brand-500); background: var(--color-brand-50); }
.load-history span, .load-history small { font-size: .8rem; color: var(--color-text-secondary); }
@media(max-width: 1000px) { .load-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media(max-width: 850px) { .load-layout, .load-charts { grid-template-columns: minmax(0, 1fr); } }
</style>
