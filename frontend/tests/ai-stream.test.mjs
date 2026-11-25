import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  aiChatPayload,
  consumeAiStream,
  isAiId,
  latestUserMessageId,
  validRecommendations,
} from '../src/utils/ai-stream.ts'

test('semantic search defaults off and keeps the existing chat payload unchanged', () => {
  const original = {
    clientMessageId: 'client-1',
    content: '推荐轻松作品',
    useGameTools: true,
    useInterests: false,
  }
  assert.deepEqual(aiChatPayload(original), original)
  assert.deepEqual(aiChatPayload({ ...original, useSemanticSearch: false }), original)
})

test('semantic search serializes only explicit consent with game tools enabled', () => {
  const input = {
    clientMessageId: 'client-2',
    content: '推荐治愈作品',
    useGameTools: true,
    useInterests: true,
    useSemanticSearch: true,
  }
  assert.deepEqual(JSON.parse(JSON.stringify(aiChatPayload(input))), input)
  assert.deepEqual(aiChatPayload({ ...input, useGameTools: false }), {
    clientMessageId: 'client-2',
    content: '推荐治愈作品',
    useGameTools: false,
    useInterests: false,
  })
})

test('regeneration payload retains the pending semantic snapshot and omits routing fields', () => {
  const pending = {
    sessionId: 'session-1',
    clientMessageId: 'client-3',
    regenerateMessageId: '19',
    useGameTools: true,
    useInterests: false,
    useSemanticSearch: true,
  }
  const snapshot = aiChatPayload(pending)
  assert.deepEqual(snapshot, {
    clientMessageId: 'client-3',
    useGameTools: true,
    useInterests: false,
    useSemanticSearch: true,
  })
  assert.deepEqual(aiChatPayload(pending), snapshot)
  assert.equal('content' in snapshot, false)
  assert.equal('regenerateMessageId' in snapshot, false)
  assert.equal('sessionId' in snapshot, false)
})

function wire(type, data) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, requestId: 'req-1', ...data })}\n\n`
}
const meta = () =>
  wire('meta', {
    sessionId: 'session-1',
    messageId: '2',
    userMessageId: '1',
    replayed: false,
  })
function body(text, size = 13) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream({
    start(controller) {
      for (let i = 0; i < bytes.length; i += size) controller.enqueue(bytes.slice(i, i + size))
      controller.close()
    },
  })
}
async function consume(text, size) {
  const received = []
  await consumeAiStream(body(text, size), 'session-1', (event) => received.push(event))
  return received
}

test('SSE frames and Chinese UTF-8 survive individual byte chunks and CRLF boundaries', async () => {
  const result = await consume(
    (meta() + wire('delta', { content: '你好，世界' }) + wire('done', {})).replaceAll('\n', '\r\n'),
    1,
  )
  assert.deepEqual(
    result.map((row) => row.type),
    ['meta', 'delta', 'done'],
  )
  assert.equal(result[1].content, '你好，世界')
})

test('terminal SSE errors are surfaced even before metadata', async () => {
  const result = await consume(
    wire('error', { code: 'CONVERSATION_BUSY', message: '会话正在生成' }),
  )
  assert.equal(result[0].code, 'CONVERSATION_BUSY')
})

test('comments and keepalive frames are ignored', async () => {
  const result = await consume(
    ': keepalive\n\n' +
      meta() +
      ': comment\n\n' +
      wire('delta', { content: 'ok' }) +
      wire('done', {}),
  )
  assert.equal(result.length, 3)
})

test('recommendations retain validated local IDs and text fields', async () => {
  const result = await consume(
    meta() +
      wire('recommendations', {
        items: [{ id: '17', name: '作品', reason: '符合偏好' }],
      }) +
      wire('delta', { content: '推荐' }) +
      wire('done', {}),
  )
  assert.deepEqual(result[1].items, [{ id: '17', name: '作品', reason: '符合偏好' }])
})

for (const [name, stream] of [
  ['premature EOF', meta() + wire('delta', { content: 'partial' })],
  ['missing metadata', wire('delta', { content: 'hello' }) + wire('done', {})],
  ['duplicate metadata', meta() + meta()],
  [
    'mismatched session',
    wire('meta', {
      sessionId: 'other',
      messageId: '2',
      userMessageId: '1',
      replayed: false,
    }),
  ],
  ['mismatched request', meta() + wire('delta', { requestId: 'other', content: 'text' })],
  ['done without content', meta() + wire('done', {})],
  ['malformed JSON', 'event: meta\ndata: not-json\n\n'],
  ['unrecognized event', wire('unknown', {})],
  ['empty delta', meta() + wire('delta', { content: '' })],
  ['event type mismatch', 'event: meta\ndata: {"type":"error","requestId":"req-1"}\n\n'],
  [
    'unsafe recommendation ID',
    meta() + wire('recommendations', { items: [{ id: '../admin', name: '作品' }] }),
  ],
]) {
  test(`rejects ${name}`, async () => {
    await assert.rejects(consume(stream))
  })
}

test('replay metadata is preserved for idempotent result reconstruction', async () => {
  const result = await consume(
    wire('meta', {
      sessionId: 'session-1',
      messageId: '2',
      userMessageId: '1',
      replayed: true,
    }) +
      wire('delta', { content: 'saved' }) +
      wire('done', {}),
  )
  assert.equal(result[0].replayed, true)
})

test('stream cleanup cancels and releases the reader on a protocol failure', async () => {
  let cancelled = false
  const source = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('event: bad\ndata: {}\n\n'))
    },
    cancel() {
      cancelled = true
    },
  })
  await assert.rejects(consumeAiStream(source, 'session-1', () => undefined))
  assert.equal(cancelled, true)
  assert.equal(source.locked, false)
})

test('signed-long IDs retain precision and reject unsafe routes', () => {
  for (const id of ['1', '9007199254740993', '9223372036854775807']) assert.equal(isAiId(id), true)
  for (const id of ['0', '-1', '01', 17, '../admin', 'https://example.com', '9223372036854775808'])
    assert.equal(isAiId(id), false)
  assert.throws(() => validRecommendations([{ id: '1', name: 'x', reason: { unsafe: true } }]))
  assert.equal(validRecommendations([{ id: '1', name: 'x', reason: null }])[0].reason, undefined)
  assert.throws(() =>
    validRecommendations(Array.from({ length: 6 }, () => ({ id: '1', name: 'x' }))),
  )
  assert.throws(() => validRecommendations([{ id: '1', name: 'x'.repeat(201) }]))
  assert.throws(() => validRecommendations([{ id: '1', name: 'x', reason: 'x'.repeat(501) }]))
})

test('latest user turn can be recovered from paginated regenerated assistant messages', () => {
  assert.equal(
    latestUserMessageId([
      { id: '101', role: 'assistant', replyToId: '98' },
      { id: '102', role: 'assistant', replyToId: '98' },
    ]),
    '98',
  )
  assert.equal(
    latestUserMessageId([
      { id: '9007199254740993', role: 'user', replyToId: null },
      { id: '99', role: 'assistant', replyToId: '98' },
    ]),
    '9007199254740993',
  )
})
