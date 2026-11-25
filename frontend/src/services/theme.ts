import { readonly, ref } from 'vue'

export type SiteTheme = 'shinku' | 'mist' | 'classic'

export const THEME_STORAGE_KEY = 'catgal.theme'
const currentTheme = ref<SiteTheme>('shinku')
const browserColors: Record<SiteTheme, string> = { shinku: '#fff9f2', mist: '#fcf8f5', classic: '#f8fafd' }

export function resolveTheme(value: unknown): SiteTheme {
  return value === 'classic' || value === 'mist' ? value : 'shinku'
}

function applyTheme(theme: SiteTheme): void {
  currentTheme.value = theme
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute(
    'content', browserColors[theme],
  )
}

export function initializeTheme(): void {
  // 主题只保存在当前浏览器；隐私模式禁用存储时仍可在本次访问中切换。
  let saved: string | null = null
  try { saved = localStorage.getItem(THEME_STORAGE_KEY) } catch { /* 使用默认主题 */ }
  applyTheme(resolveTheme(saved))
  window.addEventListener('storage', (event) => {
    if (event.key === THEME_STORAGE_KEY || event.key === null) {
      applyTheme(resolveTheme(event.newValue))
    }
  })
}

function selectTheme(theme: SiteTheme): void {
  applyTheme(theme)
  try { localStorage.setItem(THEME_STORAGE_KEY, theme) } catch { /* 不影响本次切换 */ }
}

export function useTheme() {
  return { theme: readonly(currentTheme), selectTheme }
}
