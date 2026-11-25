import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRouter, createMemoryHistory } from 'vue-router'
import {
  encodePublicId, readPublicId, gameRoute, userRoute, connectionsRoute,
  folderRoute, directoryRoute, internalReturnPath, normalizePublicLocation,
} from '../src/utils/navigation.ts'

test('login and welcome return targets retain internal queries and anchors only', () => {
  const destination = '/games/c_Y2F0Z2FsOjE6MTI?tab=comments#discussion'
  assert.equal(internalReturnPath(destination), destination)
  for (const value of ['https://outside.example', '//outside.example', '/\\outside.example', '/\t/credential', '', undefined, ['/profile']]) {
    assert.equal(internalReturnPath(value), undefined)
  }
})

test('public references round-trip without path separators or padding', () => {
  for (const id of [1, 42, 123456789, Number.MAX_SAFE_INTEGER]) {
    const reference = encodePublicId(id)
    assert.match(reference, /^c_[A-Za-z0-9_-]+$/)
    assert.equal(readPublicId(reference), id)
    assert.equal(readPublicId(String(id)), id)
    assert.equal(encodePublicId(id), reference)
  }
})

test('rejects malformed, noncanonical and unsafe identifiers', () => {
  for (const value of [undefined, null, [], ['1'], 1, '', '0', '-1', '01', '1.5', '1e2',
    '9007199254740992', 'c_', 'c_???', 'c_MQ', 'c_Y2F0Z2FsOjE6MQ==', '../1']) {
    assert.equal(readPublicId(value), 0)
  }
  for (const id of [0, -1, 1.5, Infinity, NaN, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => encodePublicId(id), RangeError)
  }
})

test('all public entry points use named routes and encoded references', () => {
  assert.deepEqual(gameRoute('12'), gameRoute(12))
  assert.deepEqual(gameRoute(12, { tab: 'comments' }, '#discussion'), {
    name: 'game-detail', params: { gameId: encodePublicId(12) }, query: { tab: 'comments' }, hash: '#discussion',
  })
  assert.equal(readPublicId(userRoute(7).params.userId), 7)
  assert.equal(readPublicId(connectionsRoute(7, 'fans').params.userId), 7)
  assert.equal(readPublicId(folderRoute(8, 7).query.owner), 7)
  assert.equal(readPublicId(directoryRoute('tags', 9).params.entryId), 9)
  assert.throws(() => gameRoute('9007199254740993'), RangeError)
  assert.throws(() => gameRoute('01'), RangeError)
})

test('legacy links preserve tabs, pagination and anchors when canonicalized', () => {
  const normalized = normalizePublicLocation({
    name: 'game-detail', params: { gameId: '12' },
    query: { resource: '42', tab: 'resources', pageNo: '2' }, hash: '#discussion',
  })
  assert.deepEqual(normalized, {
    name: 'game-detail', params: { gameId: encodePublicId(12) },
    query: { resource: encodePublicId(42), tab: 'resources', pageNo: '2' },
    hash: '#discussion', replace: true,
  })
  assert.equal(normalizePublicLocation(normalized), null)
})

test('catalog filters and publish links migrate legacy query keys', () => {
  assert.deepEqual(normalizePublicLocation({
    name: 'games', params: {}, query: { companyId: '4', tagId: '6', keyword: '故事' }, hash: '',
  }).query, { company: encodePublicId(4), tag: encodePublicId(6), keyword: '故事' })
  assert.deepEqual(normalizePublicLocation({
    name: 'resource-publish', params: {}, query: { gameId: '3' }, hash: '',
  }).query, { game: encodePublicId(3) })
  assert.equal(normalizePublicLocation({ name: 'directory', params: { kind: 'tags' }, query: {}, hash: '' }), null)
})

test('invalid public locations are rejected before requesting data', () => {
  assert.equal(normalizePublicLocation({ name: 'game-detail', params: { gameId: '../1' }, query: {}, hash: '' }), false)
  assert.equal(normalizePublicLocation({ name: 'games', params: {}, query: { company: ['1', '2'] }, hash: '' }), false)
  assert.equal(normalizePublicLocation({ name: 'home', params: {}, query: {}, hash: '' }), null)
})

test('router navigation keeps legacy links and login return targets encoded', async () => {
  const component = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/games/:gameId', name: 'game-detail', component },
      { path: '/users/:userId/connections', name: 'connections', component },
      { path: '/catalog/:kind(companies|tags)/:entryId?', name: 'directory', component },
      { path: '/folders/:folderId', name: 'folder-detail', component, meta: { requiresAuth: true } },
      { path: '/login', name: 'login', component },
      { path: '/:pathMatch(.*)*', name: 'not-found', component },
    ],
  })
  router.beforeEach(to => {
    const normalized = normalizePublicLocation(to)
    if (normalized === false) return { name: 'not-found', params: { pathMatch: ['unavailable'] } }
    if (normalized) return normalized
    if (to.meta.requiresAuth) return { name: 'login', query: { redirect: to.fullPath } }
    return true
  })
  await router.push('/games/12?tab=comments#discussion')
  assert.equal(router.currentRoute.value.fullPath, `/games/${encodePublicId(12)}?tab=comments#discussion`)
  assert.equal(router.resolve(gameRoute(12)).href, `/games/${encodePublicId(12)}`)
  await router.push('/folders/8?owner=7')
  assert.equal(router.currentRoute.value.name, 'login')
  assert.equal(router.currentRoute.value.query.redirect, `/folders/${encodePublicId(8)}?owner=${encodePublicId(7)}`)
  await router.push('/catalog/tags')
  assert.equal(router.currentRoute.value.name, 'directory')
  await router.push('/games/c_bad')
  assert.equal(router.currentRoute.value.name, 'not-found')
})
