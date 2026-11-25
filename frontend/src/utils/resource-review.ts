// 版本号按原始十进制字符串回传，不能经 Number 转换，以免大整数精度丢失。
export function hasResourceRevision(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9]\d{0,18})$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807')
}

export function isResourceConflict(error: unknown): boolean {
  return error instanceof Error && (('status' in error && error.status === 409) || ('code' in error && error.code === 409))
}

export function resourceStatusLabel(status: number | null | undefined): string {
  return status === 0 ? '失效' : status === 1 ? '有效' : status === 2 ? '待审核' : '未知状态'
}

export function resourceActionLabel(action: string): string {
  return ({ CREATE: '发布资源', EDIT: '修改资源', SUBMIT: '重新送审', APPROVE: '审核通过',
    REJECT: '设为失效', REQUEUE: '转为待审核', DELETE: '删除资源' } as Record<string, string>)[action] || '更新资源'
}

export function resourceHistoryTime(value: string | null): string {
  if (!value) return '时间未知'
  // Java LocalDateTime 为站点北京时间；显式补时区，避免海外浏览器按当地时间解释。
  const source = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value) ? value + '+08:00' : value
  const instant = new Date(source)
  if (Number.isNaN(instant.getTime())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(instant)
}

export function resourceChangedLabels(value: string | null): string[] {
  const labels: Record<string, string> = {
    name: '资源名称', remark: '说明', downloadUrl: '下载链接', extractCode: '提取码',
    password: '解压密码', fileSize: '文件大小', types: '资源类型', languages: '语言',
    platforms: '平台', status: '公开状态', content: '资源信息',
  }
  try {
    const fields: unknown = JSON.parse(value || '[]')
    if (!Array.isArray(fields)) return []
    return [...new Set(fields.filter((field): field is string => typeof field === 'string')
      .map(field => labels[field] || '资源信息'))]
  } catch { return [] }
}
