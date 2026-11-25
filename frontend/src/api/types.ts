export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
  requestId: string | null
}

export interface PageData<T> {
  total: number
  pages: number
  list: T[]
}

export class ApiError extends Error {
  readonly code: number
  readonly status: number
  readonly requestId: string | null

  constructor(options: {
    message: string
    code?: number
    status?: number
    requestId?: string | null
  }) {
    super(options.message)
    this.name = 'ApiError'
    this.code = options.code ?? 500
    this.status = options.status ?? 500
    this.requestId = options.requestId ?? null
  }
}
