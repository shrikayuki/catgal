/** Resource links are untrusted values returned by publishers. */
export function parseResourceUrl(value: string | null | undefined): URL | null {
  if (!value || /[\u0000-\u001f\u007f]/.test(value)) return null
  try {
    const url = new URL(value.trim())
    if ((url.protocol !== 'https:' && url.protocol !== 'http:') || !url.hostname || url.username || url.password) return null
    return url
  } catch {
    return null
  }
}
