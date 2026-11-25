import type { LoadTestInput, LoadTestRun } from '../api/load-tests'

const scenarios = ['games', 'search', 'resources', 'comments', 'reviews']
export function loadTestInput(value: LoadTestInput): LoadTestInput {
  if (!scenarios.includes(value.scenarioId)) throw new Error('请选择有效测试场景')
  const limits: [number, number][] = [[value.concurrency, 50], [value.durationSeconds, 60], [value.maxRequests, 5000]]
  for (const [number, maximum] of limits) {
    if (!Number.isInteger(number) || number < 1 || number > maximum) throw new Error('请填写允许范围内的整数')
  }
  const result: LoadTestInput = {
    clientRequestId: value.clientRequestId, scenarioId: value.scenarioId,
    concurrency: value.concurrency, durationSeconds: value.durationSeconds, maxRequests: value.maxRequests,
  }
  if (['resources', 'comments', 'reviews'].includes(value.scenarioId)) {
    const id = value.gameId?.trim() ?? ''
    if (!/^[1-9][0-9]{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) throw new Error('请输入有效游戏 ID')
    result.gameId = id
  }
  if (value.scenarioId === 'search') {
    const keyword = value.keyword?.trim() ?? ''
    if (!keyword || keyword.length > 100) throw new Error('检索词需为 1～100 个字符')
    result.keyword = keyword
  }
  return result
}
export const isLoadTestActive = (state: LoadTestRun['state']) =>
  ['PREPARING', 'RUNNING', 'STOPPING'].includes(state)
export function comparableLoadTests(a: LoadTestInput, b: LoadTestInput) {
  return a.scenarioId === b.scenarioId && (a.gameId ?? null) === (b.gameId ?? null) && (a.keyword ?? null) === (b.keyword ?? null) &&
    a.concurrency === b.concurrency && a.durationSeconds === b.durationSeconds && a.maxRequests === b.maxRequests
}
export function chartPoints(values: number[]): string {
  const maximum = Math.max(1, ...values)
  return values.map((value, index) => `${24 + index * 592 / Math.max(1, values.length - 1)},${160 - value * 140 / maximum}`).join(' ')
}
