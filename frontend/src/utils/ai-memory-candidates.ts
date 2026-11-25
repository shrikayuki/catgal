export function memoryCandidateRevision(value: unknown, allowZero = false): number {
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value)) {
    throw new RangeError('记忆建议版本无效，请刷新后重试')
  }
  const revision = Number(value)
  if (!Number.isSafeInteger(revision) || revision < (allowZero ? 0 : 1)) {
    throw new RangeError('记忆建议版本超出有效范围，请刷新核对或联系管理员')
  }
  return revision
}
