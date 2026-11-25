import assert from 'node:assert/strict'
import { test } from 'node:test'
import { chartPoints, comparableLoadTests, isLoadTestActive, loadTestInput } from '../src/utils/load-tests.ts'

const input = { clientRequestId: 'test-id', scenarioId: 'games', concurrency: 10, durationSeconds: 10, maxRequests: 1000 }
test('load inputs select only supported parameters', () => {
  assert.deepEqual(loadTestInput({ ...input, gameId: '2', keyword: 'ignored', url: 'https://invalid.example' }), input)
  assert.deepEqual(loadTestInput({ ...input, scenarioId: 'search', keyword: ' 测试 ' }), { ...input, scenarioId: 'search', keyword: '测试' })
})
test('load inputs enforce integer concurrency duration and request ceilings', () => {
  for (const [field, value] of [['concurrency', 51], ['concurrency', 0], ['concurrency', 1.5], ['durationSeconds', 61], ['maxRequests', 5001], ['maxRequests', NaN]])
    assert.throws(() => loadTestInput({ ...input, [field]: value }))
})
test('game ids preserve long integer strings and reject malformed ids', () => {
  assert.equal(loadTestInput({ ...input, scenarioId: 'comments', gameId: '9223372036854775807' }).gameId, '9223372036854775807')
  for (const gameId of ['', '0', '-1', '1.2', 'http://localhost', '9223372036854775808'])
    assert.throws(() => loadTestInput({ ...input, scenarioId: 'reviews', gameId }))
})
test('keyword and scenario validation is bounded', () => {
  assert.throws(() => loadTestInput({ ...input, scenarioId: 'write-user' }))
  for (const keyword of ['', ' ', 'a'.repeat(101)])
    assert.throws(() => loadTestInput({ ...input, scenarioId: 'search', keyword }))
})
test('active states include cooperative stopping', () => {
  for (const state of ['PREPARING', 'RUNNING', 'STOPPING']) assert.equal(isLoadTestActive(state), true)
  for (const state of ['FAILED', 'STOPPED', 'COMPLETED']) assert.equal(isLoadTestActive(state), false)
})
test('comparison excludes request ids but compares full scenario scale', () => {
  assert.equal(comparableLoadTests(input, { ...input, clientRequestId: 'new-id' }), true)
  assert.equal(comparableLoadTests(input, { ...input, concurrency: 20 }), false)
  assert.equal(comparableLoadTests(input, { ...input, keyword: 'different' }), false)
})
test('chart geometry supports empty and single-point samples', () => {
  assert.equal(chartPoints([]), '')
  assert.equal(chartPoints([0]), '24,160')
  assert.equal(chartPoints([10]), '24,20')
  assert.equal(chartPoints([0, 10]), '24,160 616,20')
})
