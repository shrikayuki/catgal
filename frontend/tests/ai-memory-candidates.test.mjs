import assert from 'node:assert/strict'
import { test } from 'node:test'
import { memoryCandidateRevision } from '../src/utils/ai-memory-candidates.ts'

test('candidate settings accept the initial revision zero', () => {
  assert.equal(memoryCandidateRevision('0', true), 0)
})
test('candidate actions require a positive revision', () => {
  assert.throws(() => memoryCandidateRevision('0'), RangeError)
  assert.equal(memoryCandidateRevision('1'), 1)
  assert.equal(memoryCandidateRevision('42'), 42)
})
test('candidate revision conversion preserves the safe integer boundary', () => {
  assert.equal(memoryCandidateRevision('9007199254740991'), Number.MAX_SAFE_INTEGER)
  assert.throws(() => memoryCandidateRevision('9007199254740992'), RangeError)
  assert.throws(() => memoryCandidateRevision('9223372036854775807'), RangeError)
})
test('candidate revisions reject malformed or negative values', () => {
  for (const value of ['', '-1', '1.5', '01', '1e2', ' 1 ', '+1'])
    assert.throws(() => memoryCandidateRevision(value), RangeError)
})
test('candidate revision conversion follows the string response contract', () => {
  for (const value of [null, undefined, 0, 1, {}, []])
    assert.throws(() => memoryCandidateRevision(value, true), RangeError)
})
test('candidate validation errors keep rejected input private', () => {
  assert.throws(
    () => memoryCandidateRevision('private-invalid-input'),
    (error) => error instanceof RangeError && !error.message.includes('private-invalid-input'),
  )
})
