/** Keep actionable business messages while suppressing infrastructure details. */
export function userMessage(value: unknown, status = 0, code?: unknown): string {
  if (code === 40301) return '账号已被封禁，请联系管理员'
  if (status === 401) return '登录状态已过期，请重新登录'
  if (status === 403) return '你暂时没有权限进行这项操作'
  if (status === 404) return '内容暂时无法查看，可能已被移除'
  if (status === 429) return '操作有些频繁，请稍等一会儿再试'
  if (status >= 500) return '服务暂时忙碌，请稍后重试'
  if (typeof value !== 'string') return '操作未完成，请稍后重试'
  const message = value.trim()
  const technicalDetails = /exception|traceback|stack\s*trace|\bSQL\b|JDBC|MyBatis|Redis|NullPointer|\bHTTP\b|localhost|127\.0\.0\.1|数据库|堆栈|后端|[A-Za-z]:[\\/]|\/(?:api|var|home|usr|tmp)\/|[\r\n{}]/i
  if (!message || message.length > 200 || !/[\u3400-\u9fff]/.test(message) || technicalDetails.test(message)) {
    return '操作未完成，请稍后重试'
  }
  return message
}
