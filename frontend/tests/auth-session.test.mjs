import assert from 'node:assert/strict'
import { test } from 'node:test'
import { clearAuthSessionIfCurrent, getAuthToken, onAuthSessionChanged, writeAuthSession } from '../src/services/auth-session.ts'

function mockBrowser(testContext) {
  const values = new Map()
  const target = new EventTarget()
  target.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  }
  const oldWindow = globalThis.window
  const oldStorageEvent = globalThis.StorageEvent
  globalThis.window = target
  globalThis.StorageEvent = class extends Event {
    constructor(key) { super('storage'); this.key = key }
  }
  testContext.after(() => {
    globalThis.window = oldWindow
    globalThis.StorageEvent = oldStorageEvent
  })
  return target
}

test('a completed logout or password change can clear only its original session', t => {
  mockBrowser(t)
  const first = { userId: 1, username: 'first', role: 0, token: 'first-login' }
  writeAuthSession(first)
  const reasons = []
  const stop = onAuthSessionChanged(reason => reasons.push(reason))

  assert.equal(clearAuthSessionIfCurrent(first.token, 'signed-out'), true)
  assert.equal(getAuthToken(), null)
  assert.deepEqual(reasons, ['signed-out'])

  // 使用同一账号重新登录时，也必须以请求发起时的凭据为边界。
  writeAuthSession({ ...first, token: 'new-login' })
  assert.equal(clearAuthSessionIfCurrent(first.token, 'password-changed'), false)
  assert.equal(getAuthToken(), 'new-login')
  assert.deepEqual(reasons, ['signed-out', undefined])
  assert.equal(clearAuthSessionIfCurrent('new-login', 'password-changed'), true)
  assert.deepEqual(reasons, ['signed-out', undefined, 'password-changed'])
  stop()
})

test('auth listeners support storage changes from another tab without reacting to unrelated preferences', t => {
  const target = mockBrowser(t)
  const reasons = []
  const stop = onAuthSessionChanged(reason => reasons.push(reason))

  target.dispatchEvent(new StorageEvent('catgal.theme'))
  assert.deepEqual(reasons, [])
  target.dispatchEvent(new StorageEvent('catgal.auth.session'))
  target.dispatchEvent(new StorageEvent(null))
  assert.deepEqual(reasons, [undefined, undefined])
  stop()
})
