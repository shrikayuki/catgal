import assert from 'node:assert/strict'
import { test } from 'node:test'
import { summaryRevision } from '../src/utils/ai-summary.ts'

test('summary revisions include the initial zero and safe integer range', () => {
  assert.equal(summaryRevision('0'), 0)
  assert.equal(summaryRevision('27'), 27)
  assert.equal(summaryRevision('9007199254740991'), Number.MAX_SAFE_INTEGER)
})
test('rejects malformed or negative revision values', () => {
  for (const value of ['', '-1', '1.1', '1e3', '01', ' 1 ', 'Infinity', '+1'])
    assert.throws(() => summaryRevision(value), RangeError)
})
test('rejects unsafe revision values instead of rounding', () => {
  for (const value of ['9007199254740992', '9007199254740993', '9223372036854775807'])
    assert.throws(() => summaryRevision(value), RangeError)
})
test('revision conversion accepts only the string API contract', () => {
  for (const value of [null, undefined, 0, 7, {}, []])
    assert.throws(() => summaryRevision(value), RangeError)
})
test('revision errors contain no rejected input', () => {
  assert.throws(
    () => summaryRevision('private-rejected-value'),
    (error) => error instanceof RangeError && !error.message.includes('private-rejected-value'),
  )
})
