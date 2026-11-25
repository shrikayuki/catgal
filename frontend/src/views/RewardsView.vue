<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  getDrawCost,
  getDrawRecords,
  getPrizes,
  getSignRecord,
  signIn,
  drawPrize,
  getRewardMode,
  getDrawResult,
  type Prize,
  type SignRecord,
  type DrawRecord,
} from '../api/rewards'
import { getMyPoints } from '../api/users'
import { ApiError, type PageData } from '../api/types'
import { readPendingDraw, savePendingDraw, clearPendingDraw, createDrawRequestKey } from '../utils/reward-request'
import { useAuthStore } from '../stores/auth'
import { formatDate } from '../utils/format'
import BaseButton from '../components/ui/BaseButton.vue'
import PaginationControl from '../components/ui/PaginationControl.vue'

const auth = useAuthStore()
const prizes = ref<Prize[]>([])
const cost = ref<number | null>(null)
const points = ref<number | null>(null)
const signs = ref<SignRecord | null>(null)
const records = ref<PageData<DrawRecord> | null>(null)
const page = ref(1)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const uncertain = ref(false)
const transactional = ref(false)
const draining = ref(false)
const pendingKey = ref<string | null>(null)
const calendarDate = ref(new Date())
const dateParts = computed(() =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(calendarDate.value),
)
const part = (type: Intl.DateTimeFormatPartTypes) =>
  Number(dateParts.value.find((item) => item.type === type)?.value)
const currentDay = computed(() => part('day'))
const days = computed(() =>
  Array.from({ length: new Date(part('year'), part('month'), 0).getDate() }, (_, i) => i + 1),
)
const signed = computed(() => signs.value?.signedDays.includes(currentDay.value) ?? false)
const canDraw = computed(
  () =>
    auth.isLoggedIn &&
    !busy.value &&
    !loading.value &&
    !uncertain.value &&
    !pendingKey.value &&
    !draining.value &&
    cost.value !== null &&
    cost.value > 0 &&
    points.value !== null &&
    points.value >= cost.value &&
    prizes.value.some((item) => item.drawable && (item.totalStock === 0 || item.remainStock > 0)),
)
let controller: AbortController | undefined

async function load(pageNo = page.value) {
  calendarDate.value = new Date()
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const [pool, drawCost, mode] = await Promise.all([
      getPrizes(request.signal),
      getDrawCost(request.signal),
      getRewardMode(request.signal),
    ])
    if (request.signal.aborted) return
    prizes.value = pool
    cost.value = drawCost
    transactional.value = mode.transactional
    draining.value = mode.mode === 'DRAINING'
    if (auth.isLoggedIn) {
      const [balance, signRecord, history] = await Promise.all([
        getMyPoints(request.signal, true),
        getSignRecord(request.signal),
        getDrawRecords(pageNo, request.signal),
      ])
      if (request.signal.aborted) return
      points.value = balance
      signs.value = signRecord ?? { signedDays: [], continuousDays: 0 }
      records.value = history
      page.value = pageNo
    }
  } catch (cause) {
    if (!request.signal.aborted)
      error.value = cause instanceof Error ? cause.message : '积分中心读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
async function submitSign() {
  if (busy.value || signed.value) return
  busy.value = true
  error.value = ''
  try {
    await signIn()
    message.value = transactional.value ? '签到成功，萌萌点已到账。' : '签到成功，萌萌点稍后到账。'
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '签到失败，请刷新记录核对结果'
  } finally {
    busy.value = false
  }
}
async function draw() {
  if (!canDraw.value || !window.confirm(`确认消耗 ${cost.value} 萌萌点抽奖一次？`)) return
  await submitDraw(false)
}

async function submitDraw(recover: boolean) {
  const session = auth.session
  if (!session || busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const execute = async () => {
      if (auth.session?.token !== session.token) return
      // 在发请求前持久化 UUID。超时、刷新、标签页重入都复用这个逻辑请求。
      let key = transactional.value ? readPendingDraw(localStorage, session.userId) : null
      if (transactional.value && !key) {
        key = createDrawRequestKey()
        savePendingDraw(localStorage, session.userId, key)
      }
      pendingKey.value = key
      let result
      try {
        if (recover && key) {
          try { result = await getDrawResult(key) }
          catch (cause) {
            if (!(cause instanceof ApiError) || cause.code !== 40421) throw cause
            if (auth.session?.token !== session.token) return
            result = await drawPrize(key)
          }
        } else result = await drawPrize(key ?? undefined)
      } catch (cause) {
        // 只有服务端已经保存的确定拒绝才结束该逻辑请求。网络失败继续保留原 UUID。
        if (key && cause instanceof ApiError && cause.code === 40921) {
          clearPendingDraw(localStorage, session.userId, key)
          if (auth.session?.token === session.token) pendingKey.value = null
        }
        throw cause
      }
      if (key) clearPendingDraw(localStorage, session.userId, key)
      if (auth.session?.token !== session.token) return
      pendingKey.value = null
      uncertain.value = false
      message.value = `抽中 ${result.prizeName ?? prizes.value.find((item) => item.id === result.id)?.prizeName ?? '一份惊喜'}，消耗 ${Math.abs(result.spentCutePoints)} 萌萌点。${result.requestKey ? '结果与余额已保存。' : '中奖记录与余额稍后会更新。'}`
      await load(1)
    }
    // 支持 Web Locks 的浏览器在账号维度串行化多个标签页，服务端仍执行独立的幂等和余额校验。
    if (transactional.value && navigator.locks) {
      await navigator.locks.request(`catgal.draw.${session.userId}`, execute)
    } else await execute()
  } catch (cause) {
    if (auth.session?.token !== session.token) return
    const rejected = cause instanceof ApiError && cause.code === 40921
    uncertain.value = !rejected && (!transactional.value || pendingKey.value !== null)
    error.value = `${cause instanceof Error ? cause.message : '抽奖请求失败'}${rejected ? '。可以刷新后发起下一次抽奖。' : transactional.value ? '。请恢复上次抽奖结果，系统会继续使用同一个请求号。' : '。请先刷新余额与中奖记录核对本次结果。'}`
  } finally {
    busy.value = false
  }
}
async function verifyResult() {
  await load(1)
  if (!error.value) uncertain.value = false
}
watch(
  () => auth.session?.token,
  () => {
    points.value = null
    signs.value = null
    records.value = null
    message.value = ''
    uncertain.value = false
    try { pendingKey.value = auth.session ? readPendingDraw(localStorage, auth.session.userId) : null }
    catch { pendingKey.value = null }
    void load(1)
  },
  { immediate: true },
)
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="container section workspace-page work-stack">
    <header>
      <p class="work-muted">CATGAL / REWARDS</p>
      <h1>每日相遇，收集一点幸运</h1>
      <p class="work-muted">签到、萌萌点与当月奖池。签到日历按北京时间展示。</p>
    </header>
    <div class="work-toolbar">
      <BaseButton variant="secondary" :disabled="busy || loading" @click="load()">
        刷新余额与记录
      </BaseButton>
      <span v-if="loading" role="status">正在更新…</span>
    </div>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="message" class="work-success" role="status">{{ message }}</p>
    <div v-if="auth.isLoggedIn" class="work-grid">
      <section class="work-card">
        <h2>我的萌萌点</h2>
        <strong class="rewards-balance">{{ points ?? '—' }}</strong>
        <p class="work-muted">每次抽奖消耗 {{ cost ?? '—' }} 点，记得先确认余额充足。</p>
      </section>
      <section class="work-card">
        <h2>{{ part('month') }} 月签到</h2>
        <p>连续签到 {{ signs?.continuousDays ?? 0 }} 天</p>
        <div class="sign-calendar">
          <span
            v-for="day in days"
            :key="day"
            :class="{ checked: signs?.signedDays.includes(day), today: day === currentDay }"
            :aria-label="`${day}日${signs?.signedDays.includes(day) ? '已签到' : '未签到'}`"
          >
            {{ day }}
          </span>
        </div>
        <BaseButton :disabled="busy || loading || signs === null || signed || draining" @click="submitSign">
          {{ signed ? '今日已签到' : '今日签到' }}
        </BaseButton>
      </section>
    </div>
    <section v-else class="work-card">
      <p>登录后可以签到、查看余额并参加抽奖。</p>
      <BaseButton to="/login?redirect=/rewards">前往登录</BaseButton>
    </section>
    <section>
      <div class="work-toolbar">
        <h2>当月奖池</h2>
        <BaseButton :disabled="!canDraw" :loading="busy" @click="draw">
          抽奖一次 · {{ cost ?? '—' }} 点
        </BaseButton>
      </div>
      <p v-if="draining" class="work-muted">积分系统正在切换，签到与抽奖暂时停用，请稍后刷新。</p>
      <p v-if="pendingKey && transactional" class="work-error">
        上次抽奖尚待确认，恢复时会复用原请求号。
        <button class="work-link" :disabled="busy || loading || draining" @click="submitDraw(true)">
          恢复上次抽奖结果
        </button>
      </p>
      <p v-else-if="uncertain" class="work-error">
        请核对上次抽奖结果。
        <button class="work-link" :disabled="busy || loading" @click="verifyResult">
          刷新并确认已核对
        </button>
      </p>
      <div class="work-grid">
        <article v-for="prize in prizes" :key="prize.id" class="work-card">
          <img
            v-if="prize.prizeImage"
            class="reward-image"
            :src="prize.prizeImage"
            alt=""
            loading="lazy"
          />
          <h3>{{ prize.prizeName }}</h3>
          <p class="work-muted">
            {{
              prize.totalStock === 0
                ? '库存不限'
                : `剩余 ${prize.remainStock} / ${prize.totalStock}`
            }}
            · {{ prize.drawable ? '可参与抽取' : '暂不参与抽取' }}
          </p>
        </article>
      </div>
      <p v-if="!loading && !prizes.length" class="work-empty">当月暂无奖品。</p>
    </section>
    <section v-if="auth.isLoggedIn">
      <h2>我的中奖记录</h2>
      <div class="work-table-wrap">
        <table class="work-table">
          <thead>
            <tr>
              <th>奖品</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in records?.list ?? []" :key="record.id">
              <td>{{ record.prizeName || '神秘奖品' }}</td>
              <td>{{ formatDate(record.createTime) }}</td>
            </tr>
            <tr v-if="!records?.list.length"><td colspan="2">暂无中奖记录。</td></tr>
          </tbody>
        </table>
      </div>
      <PaginationControl
        v-if="records"
        :current-page="page"
        :total-pages="records.pages"
        @change="load"
      />
    </section>
  </div>
</template>
<style scoped>
.rewards-balance {
  font-size: 3rem;
  color: var(--color-brand-700);
}
.sign-calendar {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 0.4rem;
  margin: 1rem 0;
}
.sign-calendar span {
  text-align: center;
  border: 1px solid var(--color-border);
  border-radius: 0.4rem;
  padding: 0.45rem;
  color: var(--color-text-muted);
}
.sign-calendar .checked {
  color: var(--color-brand-800);
  background: var(--color-brand-100);
}
.sign-calendar .today {
  border-color: var(--color-brand-700);
}
.reward-image {
  width: 100%;
  height: 10rem;
  object-fit: contain;
  border-radius: 0.75rem;
}
</style>
