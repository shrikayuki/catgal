import type { AiMemoryVectorSettings } from '../types/ai'

export function memoryVectorRevision(value: unknown): number {
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value)) {
    throw new RangeError('记忆语义召回版本无效，请重新读取设置')
  }
  const revision = Number(value)
  if (!Number.isSafeInteger(revision) || revision < 0) {
    throw new RangeError('记忆语义召回版本超出安全范围，请重新读取或联系管理员')
  }
  return revision
}

export function parseMemoryVectorSettings(value: unknown, botId: string): AiMemoryVectorSettings {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new RangeError('记忆语义召回设置格式异常，请重新读取设置')
  }
  const settings = value as Record<string, unknown>
  if (settings.botId !== botId || typeof settings.enabled !== 'boolean') {
    throw new RangeError('记忆语义召回设置不匹配，请重新读取设置')
  }
  memoryVectorRevision(settings.revision)
  return { botId, enabled: settings.enabled, revision: settings.revision as string }
}
