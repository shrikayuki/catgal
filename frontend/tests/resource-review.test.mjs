import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError } from '../src/api/types.ts'
import { hasResourceRevision, isResourceConflict, resourceChangedLabels, resourceHistoryTime } from '../src/utils/resource-review.ts'
import { encodePublicId, normalizePublicLocation, readPublicId, resourceEditRoute } from '../src/utils/navigation.ts'

test('resource edit links encode references and accept legacy bookmarks', () => {
  assert.deepEqual(resourceEditRoute(42), { name: 'resource-edit', params: { resourceId: encodePublicId(42) } })
  assert.equal(readPublicId(resourceEditRoute(42).params.resourceId), 42)
  assert.deepEqual(normalizePublicLocation({ name: 'resource-edit', params: { resourceId: '42' }, query: {}, hash: '' }), {
    name: 'resource-edit', params: { resourceId: encodePublicId(42) }, query: {}, hash: '', replace: true,
  })
  assert.equal(normalizePublicLocation({ name: 'resource-edit', params: { resourceId: '../42' }, query: {}, hash: '' }), false)
})

test('resource revisions retain Long precision and reject missing or malformed values', () => {
  for (const value of ['0', '1', '9007199254740993', '9223372036854775807']) assert.equal(hasResourceRevision(value), true)
  for (const value of [null, undefined, 1, '', '01', '-1', '1.0', '1e2', '9223372036854775808', '9'.repeat(100)]) {
    assert.equal(hasResourceRevision(value), false)
  }
})

test('both HTTP and business conflicts stop revision-based actions', () => {
  assert.equal(isResourceConflict(new ApiError({ message: 'stale', status: 409 })), true)
  assert.equal(isResourceConflict(new ApiError({ message: 'stale', status: 200, code: 409 })), true)
  assert.equal(isResourceConflict(new ApiError({ message: 'unavailable', status: 503 })), false)
  assert.equal(isResourceConflict(new Error('409')), false)
})

test('history shows readable field names without exposing internal or malformed content', () => {
  assert.deepEqual(resourceChangedLabels('["downloadUrl","password","futureDbColumn","downloadUrl"]'), ['下载链接', '解压密码', '资源信息'])
  for (const value of [null, '', '{}', 'not json', '[1,null]']) assert.deepEqual(resourceChangedLabels(value), [])
})

test('history interprets local server timestamps in Shanghai and keeps minute precision', () => {
  assert.equal(resourceHistoryTime('2026-11-18T10:25:00'), resourceHistoryTime('2026-11-18T02:25:00Z'))
  assert.match(resourceHistoryTime('2026-11-18T10:25:00'), /10:25/)
  assert.equal(resourceHistoryTime(null), '时间未知')
  assert.equal(resourceHistoryTime('invalid'), '时间未知')
})
