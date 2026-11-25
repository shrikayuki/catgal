<script setup lang="ts">
import { ref } from 'vue'
import { Library, Tags, Building2 } from 'lucide-vue-next'
import GameCatalogPanel from '../components/admin/GameCatalogPanel.vue'
import DictionaryPanel from '../components/admin/DictionaryPanel.vue'
import AdminWorkspace from '../components/admin/AdminWorkspace.vue'

type Section = 'games' | 'companies' | 'tags'
const section = ref<Section>('games')
const dirty = ref(false)
function select(next: Section) {
  if (section.value === next) return
  if (dirty.value && !window.confirm('放弃尚未保存的游戏资料并切换页面？')) return
  section.value = next
  dirty.value = false
}
</script>

<template>
  <AdminWorkspace>
    <header class="catalog-title"><h1>游戏与资料</h1><p>维护游戏、会社与标签，将作品与 Bangumi、VNDB 资料条目关联。</p></header>
    <div class="catalog-admin">
    <nav class="catalog-tabs" aria-label="资料管理分类"><button :class="{ active: section === 'games' }" @click="select('games')"><Library :size="18" />游戏档案</button><button :class="{ active: section === 'companies' }" @click="select('companies')"><Building2 :size="18" />会社资料</button><button :class="{ active: section === 'tags' }" @click="select('tags')"><Tags :size="18" />标签目录</button></nav>
    <GameCatalogPanel v-if="section === 'games'" @dirty="dirty = $event" />
    <DictionaryPanel v-else :key="section" :kind="section" />
    </div>
  </AdminWorkspace>
</template>

<style>
.catalog-admin { min-height: 70vh; }
.catalog-title { margin-bottom: 2rem; }
.catalog-title h1 { margin-block: .5rem .75rem; font-family: var(--font-display); font-size: var(--text-2xl); color: var(--color-brand-800); }
.catalog-title > p:last-child, .catalog-hint { color: var(--color-text-secondary); font-size: var(--text-sm); }
.catalog-tabs { display: flex; flex-wrap: wrap; gap: .5rem; margin-block: 1.5rem; padding-bottom: 1rem; border-bottom: 1px solid var(--color-border); }
.catalog-tabs button { display: inline-flex; align-items: center; gap: .5rem; border: 1px solid var(--color-border); border-radius: var(--radius-pill); padding: .7rem 1.1rem; background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; }
.catalog-tabs button.active { border-color: var(--color-brand-700); background: var(--color-brand-700); color: white; }
.catalog-admin button:disabled { opacity: .5; cursor: not-allowed; }
.catalog-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem; margin-block: 1rem; }
.catalog-admin input:not([type=checkbox]), .catalog-admin textarea, .catalog-admin select { max-width: 100%; min-width: 0; border: 1px solid var(--color-border-strong); border-radius: var(--radius-sm); padding: .75rem; background: var(--color-surface); color: var(--color-text-primary); }
.catalog-toolbar > input { flex: 1; min-width: 10rem; }
.catalog-admin input:focus-visible, .catalog-admin textarea:focus-visible, .catalog-admin select:focus-visible, .catalog-admin button:focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.catalog-card { padding: 1.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-sm); }
.catalog-error { padding: 1rem; border: 1px solid var(--color-danger); border-radius: var(--radius-sm); background: #fff6f7; color: var(--color-danger); }
.catalog-table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); }
.catalog-table { width: 100%; text-align: left; border-collapse: collapse; white-space: nowrap; }
.catalog-table th { background: var(--color-surface-soft); font-size: var(--text-sm); color: var(--color-text-secondary); }
.catalog-table th, .catalog-table td { padding: 1rem; border-bottom: 1px solid var(--color-border); }
.catalog-table td:nth-child(2) { min-width: 12rem; max-width: 28rem; white-space: normal; overflow-wrap: anywhere; }
.catalog-table tr:last-child td { border-bottom: 0; }
.catalog-block { display: block; margin-top: .35rem; color: var(--color-text-muted); }
.catalog-actions { display: flex; align-items: center; gap: 1rem; }
.catalog-danger { border: 0; background: transparent; color: var(--color-danger); padding: .5rem; cursor: pointer; }
.catalog-status { display: inline-block; padding: .35rem .7rem; border-radius: var(--radius-pill); background: var(--color-surface-soft); color: var(--color-text-secondary); font-size: var(--text-xs); }
.catalog-status.status-1 { background: #eaf6ef; color: var(--color-success); }
.catalog-status.status-2 { background: #fff1f2; color: var(--color-danger); }
.editor-heading h2 { flex: 1; font-size: var(--text-xl); overflow-wrap: anywhere; }
.editor-form > fieldset, .external-controls { min-width: 0; border: 0; padding: 0; margin: 0; }
.editor-form > fieldset > label, .editor-grid > label, .external-controls > label { display: grid; gap: .5rem; margin-bottom: 1.25rem; font-size: var(--text-sm); font-weight: 600; }
.editor-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1.5rem; }
.editor-form textarea { resize: vertical; }
.relation-picker { min-width: 0; padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.relation-picker legend { padding: 0 .5rem; font-size: var(--text-sm); color: var(--color-brand-700); }
.relation-picked { display: flex; flex-wrap: wrap; gap: .4rem; }
.relation-picked button { padding: .3rem .65rem; border: 0; border-radius: var(--radius-pill); background: var(--color-brand-50); color: var(--color-brand-800); cursor: pointer; }
.relation-options { display: grid; max-height: 15rem; overflow-y: auto; gap: .65rem; }
.relation-options label { display: flex; align-items: center; gap: .5rem; overflow-wrap: anywhere; }
.relation-options small { color: var(--color-text-muted); }
.status-controls { border-top: 1px solid var(--color-border); padding-top: 1.25rem; }
.external-links { display: grid; gap: .75rem; padding: 0; list-style: none; }
.external-links li { display: flex; justify-content: space-between; align-items: center; gap: 1rem; padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.external-links a { overflow-wrap: anywhere; color: var(--color-brand-700); }
.external-controls { margin-top: 1.5rem; }
.external-controls select { width: min(100%, 20rem); }
.external-results { display: grid; gap: .65rem; margin-block: 1.5rem; }
.external-result { display: grid; gap: .4rem; padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); text-align: left; background: var(--color-surface); cursor: pointer; }
.external-result:hover { border-color: var(--color-brand-400); background: var(--color-brand-50); }
.external-result small { color: var(--color-text-secondary); }
.external-result span { color: var(--color-brand-600); font-size: var(--text-xs); }
.external-preview { display: grid; grid-template-columns: minmax(0, 10rem) minmax(0, 1fr); gap: 1.5rem; }
.external-preview > div:only-child { grid-column: 1 / -1; }
.external-cover { align-self: start; aspect-ratio: 3 / 4; }
.external-description { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow-y: auto; line-height: 1.9; }
@media (max-width: 720px) { .editor-grid, .external-preview { grid-template-columns: minmax(0, 1fr); } .external-cover { max-width: 10rem; } .catalog-card { padding: 1rem; } .catalog-table th, .catalog-table td { padding: .8rem; } .external-links li { align-items: flex-start; flex-direction: column; } }
</style>
