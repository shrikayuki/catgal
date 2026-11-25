import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resolveLikeState } from '../src/utils/like-state.ts'

test('a new like increments the count and selects the target', () => {
  assert.deepEqual(resolveLikeState(4, true, true), { isLike: true, likeCount: 5 })
})
test('removing an existing like decrements the count', () => {
  assert.deepEqual(resolveLikeState(4, false, true), { isLike: false, likeCount: 3 })
})
test('duplicate like response false preserves count and resolves to liked', () => {
  assert.deepEqual(resolveLikeState(4, true, false), { isLike: true, likeCount: 4 })
})
test('duplicate unlike response false preserves count and resolves to unliked', () => {
  assert.deepEqual(resolveLikeState(4, false, false), { isLike: false, likeCount: 4 })
})
test('missing counts initialize to zero before applying a like', () => {
  assert.deepEqual(resolveLikeState(null, true, true), { isLike: true, likeCount: 1 })
})
test('unlike never displays a negative count', () => {
  assert.deepEqual(resolveLikeState(0, false, true), { isLike: false, likeCount: 0 })
  assert.deepEqual(resolveLikeState(null, false, true), { isLike: false, likeCount: 0 })
})
