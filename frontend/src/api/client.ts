import { clearAuthSession, getAuthToken } from '../services/auth-session'
import { ApiError, type ApiResponse } from './types'

type RequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
}

const rawBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() || '/api'
const baseUrl = rawBaseUrl.replace(/\/$/, '')

async function parseResponse<T>(response: Response): Promise<ApiResponse<T>> {
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    throw new ApiError({
      message: response.ok ? '服务返回了无法识别的数据格式' : `请求失败（HTTP ${response.status}）`,
      status: response.status,
    })
  }
  return (await response.json()) as ApiResponse<T>
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = getAuthToken()
  const headers = new Headers(options.headers)
  const isFormData = options.body instanceof FormData
  const requestBody =
    options.body === undefined
      ? undefined
      : options.body instanceof FormData
        ? options.body
        : JSON.stringify(options.body)

  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.body !== undefined && !isFormData) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(`${baseUrl}${path.startsWith('/') ? path : `/${path}`}`, {
      ...options,
      headers,
      credentials: 'same-origin',
      body: requestBody,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError({ message: '暂时无法连接服务，请稍后重试', status: 0, code: 0 })
  }

  const payload = await parseResponse<T>(response)
  if (response.status === 401 || payload.code === 401) clearAuthSession()

  if (!response.ok || payload.code !== 200) {
    throw new ApiError({
      message: payload.msg || `请求失败（HTTP ${response.status}）`,
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
