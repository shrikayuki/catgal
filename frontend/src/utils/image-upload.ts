export const imageAccept = 'image/jpeg,image/png,image/gif,image/webp'
export const maxImageBytes = 2 * 1024 * 1024

const imageTypes: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
}

export function localImageError(file: Pick<File, 'name' | 'type' | 'size'>): string {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!imageTypes[extension] || file.type.toLowerCase() !== imageTypes[extension]) {
    return '请选择 JPG、PNG、GIF 或 WebP 图片'
  }
  if (file.size <= 0) return '这张图片是空文件，请重新选择'
  if (file.size > maxImageBytes) return '图片大小请控制在 2 MB 以内'
  return ''
}

/** Only these same-origin images require the current account's authorization. */
export function privateAiImagePath(value: string): string | null {
  return /^\/api\/files\/ai-bot-(avatars|portraits)\/[0-9a-f]{32}$/.test(value)
    ? value.slice('/api'.length)
    : null
}
