export function summaryRevision(value: unknown): number {
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value)) {
    throw new RangeError('摘要版本无效，请重新加载后重试')
  }
  const revision = Number(value)
  if (!Number.isSafeInteger(revision) || revision < 0) {
    throw new RangeError('摘要版本超出安全范围，请重新加载或联系管理员')
  }
  return revision
}
