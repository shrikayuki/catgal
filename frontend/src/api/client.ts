import { clearAuthSessionIfCurrent, getAuthToken } from '../services/auth-session'
import { ApiError, type ApiResponse } from './types'
import { userMessage } from '../utils/user-message'
import { accountBannedCode, shouldResetAuth } from '../utils/account-status'

type RequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
  authToken?: string
  handleAuthFailure?: boolean
}

const rawBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() || '/api'
export const baseUrl = rawBaseUrl.replace(/\/$/, '')

async function parseResponse<T>(response: Response): Promise<ApiResponse<T>> {
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    throw new ApiError({
      message: userMessage(null, response.status),
      status: response.status,
    })
  }
  try {
    const payload = await response.json() as ApiResponse<T>
    if (!payload || typeof payload.code !== 'number') throw new Error()
    return payload
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError({ message: '内容暂时无法加载，请稍后重试', status: response.status })
  }
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { authToken: token = getAuthToken(), handleAuthFailure = true, body, ...request } = options
  const headers = new Headers(request.headers)
  const isFormData = body instanceof FormData
  const requestBody = body === undefined || isFormData ? body : JSON.stringify(body)

  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (body !== undefined && !isFormData) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`${baseUrl}${path.startsWith('/') ? path : `/${path}`}`, {
      ...request,
      headers,
      credentials: 'same-origin',
      body: requestBody,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError({ message: '暂时无法连接服务，请稍后重试', status: 0, code: 0 })
  }

  // 先处理 HTTP 401，兼容代理返回的非 JSON 错误，再从正文识别封禁业务码。
  if (handleAuthFailure) applyAuthFailure(response.status, undefined, token)
  const payload = await parseResponse<T>(response)
  if (handleAuthFailure) applyAuthFailure(response.status, payload.code, token)

  if (!response.ok || payload.code !== 200) {
    throw new ApiError({
      message: userMessage(payload.msg, response.status, payload.code),
      code: payload.code,
      status: response.status,
      requestId: payload.requestId,
    })
  }

  return payload.data
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    apiRequest<T>(path, { ...options, method: 'DELETE', body }),
}

/** 私有图片和 SSE 使用相同的认证失效处理，不重放任何写请求。 */
export function applyAuthFailure(status: number, code: unknown, token: string | null): void {
  if (token && shouldResetAuth(status, code, token, getAuthToken())) {
    clearAuthSessionIfCurrent(token, code === accountBannedCode ? 'account-banned' : undefined)
  }
}

// 原始 fetch 调用方传入发起请求时的 Token，复用会话切换保护和经过净化的错误文案。
export async function responseError(response: Response, token: string | null): Promise<ApiError> {
  let payload: { code?: number; msg?: string; requestId?: string } = {}
  applyAuthFailure(response.status, undefined, token)
  if (response.headers.get('content-type')?.includes('application/json')) {
    try {
      const value: unknown = await response.json()
      if (value && typeof value === 'object') {
        const row = value as Record<string, unknown>
        payload = { code: typeof row.code === 'number' ? row.code : undefined,
          msg: typeof row.msg === 'string' ? row.msg : undefined,
          requestId: typeof row.requestId === 'string' ? row.requestId : undefined }
      }
    }
    catch { /* 非法错误正文沿用 HTTP 状态，避免泄露原始 HTML。 */ }
  }
  applyAuthFailure(response.status, payload.code, token)
  return new ApiError({ message: userMessage(payload.msg, response.status, payload.code),
    status: response.status, code: payload.code, requestId: payload.requestId })
}
