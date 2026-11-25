import assert from 'node:assert/strict'
import { test } from 'node:test'
import { userMessage } from '../src/utils/user-message.ts'

test('retains short actionable business messages', () => {
  assert.equal(userMessage(' 今天已经签到啦 '), '今天已经签到啦')
  assert.equal(userMessage('密码不正确，请重新输入', 200), '密码不正确，请重新输入')
})

test('technical responses use a readable fallback without echoing paths or exceptions', () => {
  for (const message of [null, undefined, {}, '', 'Internal Server Error', 'SQL 查询失败',
    'Redis 连接失败', '数据库连接失败', 'java.lang.NullPointerException', '请求失败（HTTP 502）',
    '无法读取 C:\\private\\file', '找不到 /api/game/123', '出错了\nstack trace', '错误 {"id":123}', '字'.repeat(201)]) {
    assert.equal(userMessage(message), '操作未完成，请稍后重试')
  }
})

test('HTTP failures provide actionable status messages', () => {
  assert.match(userMessage('private detail', 401), /重新登录/)
  assert.match(userMessage('private detail', 403), /权限/)
  assert.match(userMessage('private detail', 404), /移除/)
  assert.match(userMessage('private detail', 429), /频繁/)
  assert.equal(userMessage('private detail', 503), '服务暂时忙碌，请稍后重试')
})
