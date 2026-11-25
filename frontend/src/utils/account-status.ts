// 与后端 ErrorInfo.Code.ACCOUNT_BANNED 保持一致，普通权限不足仍使用通用 403。
export const accountBannedCode = 40301

/** 只清理发起请求时的同一会话，避免旧请求返回时误退出刚切换的新账号。 */
export function shouldResetAuth(status: number, code: unknown, requestToken: string | null, currentToken: string | null): boolean {
  return Boolean(requestToken && requestToken === currentToken && (status === 401 || code === 401 || code === accountBannedCode))
}

/** 将分钟精度的北京时间表单值转换为 UTC ISO 字符串，后端仍会再次检查期限。 */
export function banDeadline(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null
  const instant = new Date(value + ':00+08:00')
  if (Number.isNaN(instant.getTime()) || instant.getTime() <= Date.now()) return null
  // 日期构造可能自动顺延非法日期，回转到北京时间核对原输入，拒绝 2 月 30 日等值。
  const normalized = new Date(instant.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16)
  return normalized === value ? instant.toISOString() : null
}

/** 管理页统一按北京时间展示封禁期限及审计时间，空封禁期限显示为永久。 */
export function formatAccountTime(value: string | null): string {
  if (!value) return '永久'
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) return '时间无法读取'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(instant)
}
