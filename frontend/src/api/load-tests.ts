import { api } from './client'

export interface LoadTestInput {
  clientRequestId: string
  scenarioId: string
  concurrency: number
  durationSeconds: number
  maxRequests: number
  gameId?: string
  keyword?: string
}
export interface LoadTestCatalog {
  enabled: boolean
  target: string
  maxConcurrency: number
  maxDurationSeconds: number
  maxRequests: number
  retainedRuns: number
  activeJobId: string | null
  scenarios: { id: string; label: string; path: string; requiresGame: boolean; requiresKeyword: boolean }[]
}
export interface LoadTestPoint {
  second: number
  completed: number
  failed: number
  rps: number
  p95Ms: number
}
export interface LoadTestRun {
  id: string
  config: LoadTestInput
  actorId: number
  state: 'PREPARING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'COMPLETED' | 'FAILED'
  stopReason: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  elapsedSeconds: number
  target: string
  metrics: {
    started: number; completed: number; succeeded: number; failed: number; cancelled: number; inFlight: number
    successRate: number; rps: number; averageMs: number; p50Ms: number; p95Ms: number; p99Ms: number; maxMs: number
  }
  timeline: LoadTestPoint[]
  errors: Record<string, number>
}
export const getLoadTestCatalog = (signal?: AbortSignal) =>
  api.get<LoadTestCatalog>('/admin/load-tests/scenarios', { signal })
export const getLoadTestHistory = (signal?: AbortSignal) =>
  api.get<LoadTestRun[]>('/admin/load-tests', { signal })
export const getLoadTest = (id: string, signal?: AbortSignal) =>
  api.get<LoadTestRun>(`/admin/load-tests/${encodeURIComponent(id)}`, { signal })
export const startLoadTest = (input: LoadTestInput) => api.post<LoadTestRun>('/admin/load-tests', input)
export const stopLoadTest = (id: string) => api.post<LoadTestRun>(`/admin/load-tests/${encodeURIComponent(id)}/stop`)
