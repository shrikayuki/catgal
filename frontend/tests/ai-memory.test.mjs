import assert from 'node:assert/strict'
import { test } from 'node:test'
import { confirmedMemoryInput } from '../src/utils/ai-memory.ts'

test('memory writes require an explicit confirmation', () => {
  assert.throws(() => confirmedMemoryInput('喜欢日常作品', 'preference', false), /确认/)
})
test('normalizes confirmed memory content and preserves supported type', () => {
  assert.deepEqual(confirmedMemoryInput('  喜欢日常作品  ', 'preference', true), {
    content: '喜欢日常作品',
    memoryType: 'preference',
  })
  assert.deepEqual(confirmedMemoryInput('已通关作品 A', 'fact', true), {
    content: '已通关作品 A',
    memoryType: 'fact',
  })
})
test('rejects empty content and content longer than the backend limit', () => {
  assert.throws(() => confirmedMemoryInput(' \n ', 'fact', true))
  assert.throws(() => confirmedMemoryInput('字'.repeat(1001), 'fact', true))
})
test('counts Unicode code points consistently with the Python limit', () => {
  assert.equal(
    Array.from(confirmedMemoryInput('🌸'.repeat(1000), 'preference', true).content).length,
    1000,
  )
  assert.throws(() => confirmedMemoryInput('🌸'.repeat(1001), 'preference', true))
})
test('rejects unsupported memory types', () => {
  assert.throws(() => confirmedMemoryInput('内容', 'system', true))
})
