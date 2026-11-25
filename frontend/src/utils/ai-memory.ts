import type { AiMemoryInput } from '../types/ai'

export function confirmedMemoryInput(
  content: string,
  memoryType: string,
  confirmed: boolean,
): AiMemoryInput {
  if (!confirmed) throw new Error('请先确认将这段内容保存为当前机器人的私有记忆')
  const normalized = content.trim()
  if (!normalized || Array.from(normalized).length > 1000)
    throw new Error('记忆内容须为 1 到 1000 个字符')
  if (memoryType !== 'preference' && memoryType !== 'fact') throw new Error('请选择偏好或事实类型')
  return { content: normalized, memoryType }
}
