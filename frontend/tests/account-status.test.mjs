import assert from 'node:assert/strict'
import { test } from 'node:test'
import { userMessage as accountErrorMessage } from '../src/utils/user-message.ts'
import { shouldResetAuth, banDeadline, formatAccountTime } from '../src/utils/account-status.ts'

test('ban and expired credentials clear only the exact requesting session', () => {
  assert.equal(shouldResetAuth(403, 40301, 'old', 'old'), true)
  assert.equal(shouldResetAuth(401, 401, 'old', 'old'), true)
  assert.equal(shouldResetAuth(200, 401, 'old', 'old'), true)
  for (const code of [403, 400, 409, 500, undefined]) assert.equal(shouldResetAuth(403, code, 'old', 'old'), false)
  assert.equal(shouldResetAuth(403, 40301, 'old', 'new'), false)
  assert.equal(shouldResetAuth(403, 40301, null, null), false)
})

test('ban message is explicit while other permission and infrastructure errors remain safe', () => {
  assert.equal(accountErrorMessage('private reason', 403, 40301), '账号已被封禁，请联系管理员')
  assert.match(accountErrorMessage('private reason', 403, 403), /权限/)
  assert.equal(accountErrorMessage('SQL secret', 500, 500), '服务暂时忙碌，请稍后重试')
})

test('deadline input has explicit Shanghai timezone and requires a future value', () => {
  assert.equal(banDeadline('2099-05-20T08:30'), '2099-05-20T00:30:00.000Z')
  assert.equal(banDeadline('2000-01-01T00:00'), null)
  assert.equal(banDeadline('not-a-date'), null)
  assert.equal(banDeadline('2099-02-30T08:30'), null)
  assert.equal(banDeadline('2099-05-20T08:30Z'), null)
})

test('deadline display is deterministic and handles malformed values', () => {
  assert.equal(formatAccountTime(null), '永久')
  assert.equal(formatAccountTime('invalid'), '时间无法读取')
  assert.match(formatAccountTime('2099-05-20T00:30:00Z'), /08:30/)
})
