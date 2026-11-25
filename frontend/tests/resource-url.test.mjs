import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseResourceUrl } from '../src/utils/resource-url.ts'

test('normalizes absolute HTTP and HTTPS resource URLs', () => {
  assert.equal(parseResourceUrl(' https://downloads.example.com/package?code=test ')?.href,
    'https://downloads.example.com/package?code=test')
  assert.equal(parseResourceUrl('http://downloads.example.com/file')?.hostname, 'downloads.example.com')
})

for (const value of [null, undefined, '', '//evil.example/file', 'javascript:alert(1)', 'data:text/html,test',
  'file:///C:/file.zip', 'ftp://example.com/file', 'https://user:password@example.com/file',
  'https://exam\tple.com/file', 'https://example.com/\nfile', 'not a url']) {
  test(`rejects unsafe or malformed resource URL ${JSON.stringify(value)}`, () => {
    assert.equal(parseResourceUrl(value), null)
  })
}
