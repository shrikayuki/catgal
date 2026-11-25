import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { test } from 'node:test'

async function publicTemplates() {
  const templates = []
  for (const directory of ['views', 'components/content', 'components/ai', 'components/layout']) {
    const base = new URL(`../src/${directory}/`, import.meta.url)
    for (const file of await readdir(base, { recursive: true })) {
      if (!file.endsWith('.vue') || file.startsWith('Admin')) continue
      const source = await readFile(new URL(file, base), 'utf8')
      templates.push({
        file: `${directory}/${file}`,
        template: source.slice(source.indexOf('<template>'), source.lastIndexOf('</template>')),
      })
    }
  }
  return templates
}

test('public templates display readable names instead of raw record identifiers', async () => {
  for (const { file, template } of await publicTemplates()) {
    assert.doesNotMatch(template, /\{\{\s*(?:[A-Za-z_$][\w$]*\.)*(?:id|[A-Za-z_$][\w$]*Id)\s*\}\}/, file)
    assert.doesNotMatch(template, /:to="`\/(?:games|users|folders|catalog)\//, file)
  }
})

test('public templates keep implementation commentary out of user-facing copy', async () => {
  for (const { file, template } of await publicTemplates()) {
    assert.doesNotMatch(template, /前端|后端|数据库|模拟(?:业务)?数据|Elasticsearch|MySQL|请求键|clientMessageId/, file)
  }
})
