import assert from 'node:assert/strict'
import { test } from 'node:test'
import { localImageError, maxImageBytes, privateAiImagePath } from '../src/utils/image-upload.ts'

test('local image selection accepts supported types and the size boundary', () => {
  for (const [name, type] of [['photo.JPG', 'image/jpeg'], ['photo.jpeg', 'image/jpeg'],
    ['photo.png', 'image/png'], ['photo.gif', 'image/gif'], ['photo.webp', 'image/webp']]) {
    assert.equal(localImageError({ name, type, size: maxImageBytes }), '')
  }
})

test('local image selection rejects invalid metadata before uploading', () => {
  for (const file of [
    { name: 'photo.svg', type: 'image/svg+xml', size: 10 },
    { name: 'photo.png.exe', type: 'image/png', size: 10 },
    { name: 'photo.png', type: 'image/jpeg', size: 10 },
    { name: 'photo.png', type: 'image/png', size: 0 },
    { name: 'photo.png', type: 'image/png', size: maxImageBytes + 1 },
  ]) assert.notEqual(localImageError(file), '')
})

test('private image authorization applies only to exact same-origin managed paths', () => {
  const id = 'a'.repeat(32)
  assert.equal(privateAiImagePath(`/api/files/ai-bot-avatars/${id}`), `/files/ai-bot-avatars/${id}`)
  assert.equal(privateAiImagePath(`/api/files/ai-bot-portraits/${id}`), `/files/ai-bot-portraits/${id}`)
  for (const path of [`https://example.com/api/files/ai-bot-avatars/${id}`,
    `//example.com/api/files/ai-bot-avatars/${id}`, `/api/files/ai-bot-avatars/${id}?token=x`,
    `/api/files/ai-bot-avatars/../${id}`, `/api/files/ai-bot-avatars/${id}#fragment`,
    '/api/files/ai-bot-avatars/123', '/assets/ai/avatar.png', 'blob:https://example.com/id']) {
    assert.equal(privateAiImagePath(path), null)
  }
})
