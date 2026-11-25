const registrationKey = 'catgal.new-registration'
let pendingUsername = ''

// 注册后需要重新登录。仅在当前标签页暂存用户名，刷新登录页也能续上引导。
export function recentRegistration(): string {
  try {
    return sessionStorage.getItem(registrationKey) ?? pendingUsername
  } catch {
    return pendingUsername
  }
}

export function rememberRegistration(username: string): void {
  pendingUsername = username
  try {
    sessionStorage.setItem(registrationKey, username)
  } catch { /* 禁用浏览器存储时仍支持当前页面。 */ }
}

export function takeRegistration(username: string): boolean {
  if (!username || recentRegistration() !== username) return false
  pendingUsername = ''
  try {
    sessionStorage.removeItem(registrationKey)
  } catch { /* 当前页面的标记已清除。 */ }
  return true
}
