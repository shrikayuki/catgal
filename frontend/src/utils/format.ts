const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
})

const compactNumberFormatter = new Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 1,
})

export function formatDate(value: string | null | undefined): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : dateFormatter.format(date)
}

export function formatCompactNumber(value: number | null | undefined): string {
  return compactNumberFormatter.format(value ?? 0)
}

export function getPlatformLabel(value: string): string {
  const labels: Record<string, string> = {
    win: 'Windows',
    mac: 'macOS',
    linux: 'Linux',
    android: 'Android',
    ios: 'iOS',
  }
  return labels[value] ?? value
}

export function getRecommendLabel(value: number | null): string {
  const labels: Record<number, string> = {
    1: '不推荐',
    2: '谨慎推荐',
    3: '值得一试',
    4: '推荐',
    5: '强烈推荐',
  }
  return value === null ? '未标记' : (labels[value] ?? `推荐度 ${value}`)
}

export function getPlayStatusLabel(value: number | null): string {
  const labels: Record<number, string> = {
    1: '想玩',
    2: '在玩',
    3: '搁置',
    4: '弃坑',
    5: '通关',
    6: '全成就',
  }
  return value === null ? '游玩状态未知' : (labels[value] ?? `状态 ${value}`)
}
