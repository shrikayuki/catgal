import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readPendingDraw, savePendingDraw, clearPendingDraw, createDrawRequestKey } from '../src/utils/reward-request.ts'

function storage() {
  const values = new Map()
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) }
}
const first = 'f0000000-0000-0000-0000-000000000001'
const second = 'f0000000-0000-0000-0000-000000000002'

test('draw keys survive reload and are isolated by account', () => {
  const memory = storage()
  savePendingDraw(memory, 1, first.toUpperCase())
  assert.equal(readPendingDraw(memory, 1), first)
  assert.equal(readPendingDraw(memory, 2), null)
  clearPendingDraw(memory, 2, first)
  assert.equal(readPendingDraw(memory, 1), first)
})
test('an older completion cannot erase a newer draw from another tab', () => {
  const memory = storage()
  savePendingDraw(memory, 1, first)
  savePendingDraw(memory, 1, second)
  clearPendingDraw(memory, 1, first)
  assert.equal(readPendingDraw(memory, 1), second)
  clearPendingDraw(memory, 1, second)
  assert.equal(readPendingDraw(memory, 1), null)
})
test('invalid and oversized persisted values cannot become request identifiers', () => {
  const memory = storage()
  for (const value of ['', '../unsafe', 'a'.repeat(10000)]) {
    assert.throws(() => savePendingDraw(memory, 1, value))
    memory.setItem('catgal.draw.pending.v1:1', value)
    assert.equal(readPendingDraw(memory, 1), null)
  }
})
test('LAN HTTP can generate v4 identifiers when randomUUID is unavailable', () => {
  let calls = 0
  const key = createDrawRequestKey({ getRandomValues(bytes) { calls++; bytes.fill(255); return bytes } })
  assert.equal(calls, 1)
  assert.equal(key, 'ffffffff-ffff-4fff-bfff-ffffffffffff')
  assert.match(createDrawRequestKey(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})
