import assert from 'node:assert/strict'
import { test } from 'node:test'
import { memoryVectorRevision, parseMemoryVectorSettings } from '../src/utils/ai-memory-vector.ts'

test('memory vector settings accept the initial revision zero', () => {
  assert.equal(memoryVectorRevision('0'), 0)
  assert.deepEqual(
    parseMemoryVectorSettings({ botId: '11', enabled: false, revision: '0' }, '11'),
    {
      botId: '11',
      enabled: false,
      revision: '0',
    },
  )
})

test('memory vector revision conversion preserves safe integer boundaries', () => {
  assert.equal(memoryVectorRevision('9007199254740991'), Number.MAX_SAFE_INTEGER)
  for (const revision of ['9007199254740992', '9223372036854775807'])
    assert.throws(() => memoryVectorRevision(revision), RangeError)
})

test('memory vector revisions require canonical nonnegative decimal strings', () => {
  for (const value of ['', '-1', '1.1', '01', '1e2', ' 1 ', '+1', 0, null, undefined, {}, []])
    assert.throws(() => memoryVectorRevision(value), RangeError)
})

test('memory vector responses require the current bot and a boolean authorization', () => {
  for (const value of [
    null,
    [],
    {},
    { botId: '12', enabled: true, revision: '1' },
    { botId: '11', enabled: 'false', revision: '1' },
    { botId: '11', enabled: true, revision: 1 },
  ])
    assert.throws(() => parseMemoryVectorSettings(value, '11'), RangeError)
  assert.deepEqual(
    parseMemoryVectorSettings(
      { botId: '11', enabled: true, revision: '2', ignored: 'private' },
      '11',
    ),
    {
      botId: '11',
      enabled: true,
      revision: '2',
    },
  )
})

test('memory vector validation errors keep rejected values private', () => {
  assert.throws(
    () => memoryVectorRevision('private-invalid-input'),
    (error) => error instanceof RangeError && !error.message.includes('private-invalid-input'),
  )
})
