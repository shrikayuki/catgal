<script setup lang="ts">
import { gameRoute, readPublicId } from '../utils/navigation'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ArrowLeft, BookOpen, CheckCircle2, FileArchive, Link2, Send, SlidersHorizontal } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { editResource, getEditableResource, publishResource, type EditableResource } from '../api/resources'
import { parseResourceUrl } from '../utils/resource-url'
import { hasResourceRevision, isResourceConflict, resourceStatusLabel } from '../utils/resource-review'
import { getPlatformLabel } from '../utils/format'
import PublicGamePicker from '../components/content/PublicGamePicker.vue'
import BaseButton from '../components/ui/BaseButton.vue'
const route = useRoute(),
  auth = useAuthStore()
const canPublish = computed(() => auth.session?.role === 1 || auth.session?.role === 2)
const editing = computed(() => route.name === 'resource-edit')
const resourceId = computed(() => readPublicId(route.params.resourceId))
const gameId = ref<number | null>(null)
const editable = ref<EditableResource | null>(null)
const form = reactive({
  name: '',
  remark: '',
  downloadUrl: '',
  extractCode: '',
  password: '',
  fileSize: '',
})
const types = ref<string[]>(['pc']),
  languages = ref<string[]>(['中文']),
  platforms = ref<string[]>(['win'])
const busy = ref(false),
  error = ref(''),
  success = ref(false)
const successHeading = ref<HTMLHeadingElement | null>(null)
const loading = ref(false), loadError = ref(''), conflict = ref(false)
const typeOptions = computed(() => options(['pc', '生肉', '汉化', '手机', '补丁', '模拟器', '其他'], editable.value?.types))
const languageOptions = computed(() => options(['中文', '日语', '英语', '其他'], editable.value?.languages))
const platformOptions = computed(() => options(['win', 'mac', 'linux', 'android', 'ios', '其他'], editable.value?.platforms))
const sections = computed(() => [
  { id: 'resource-basics', label: '作品与说明', icon: BookOpen, ready: Boolean(gameId.value && form.name.trim()) },
  { id: 'resource-download', label: '下载信息', icon: Link2, ready: Boolean(parseResourceUrl(form.downloadUrl) && form.fileSize.trim()) },
  { id: 'resource-compatibility', label: '适用范围', icon: SlidersHorizontal, ready: [types.value, languages.value, platforms.value].every((values) => values.length && values.join(',').length <= 100) },
])
const filledSections = computed(() => sections.value.filter((section) => section.ready).length)
let controller: AbortController | undefined
let viewVersion = 0

function options(defaults: string[], stored?: string): string[] {
  // 保留历史资源中的自定义分类，编辑时取消勾选后仍可重新选择。
  return [...new Set([...defaults, ...(stored?.split(',').filter(Boolean) ?? [])])]
}

function fillResource(resource: EditableResource): void {
  editable.value = resource
  gameId.value = resource.gameId
  for (const key of ['name', 'remark', 'downloadUrl', 'extractCode', 'password', 'fileSize'] as const) {
    form[key] = resource[key] ?? ''
  }
  types.value = resource.types.split(',').filter(Boolean)
  languages.value = resource.languages.split(',').filter(Boolean)
  platforms.value = resource.platforms.split(',').filter(Boolean)
}

async function load(): Promise<void> {
  viewVersion++
  controller?.abort()
  const request = new AbortController()
  controller = request
  busy.value = false
  success.value = false
  conflict.value = false
  error.value = ''
  loadError.value = ''
  editable.value = null
  if (!editing.value) {
    gameId.value = readPublicId(route.query.game) || null
    Object.assign(form, { name: '', remark: '', downloadUrl: '', extractCode: '', password: '', fileSize: '' })
    types.value = ['pc']; languages.value = ['中文']; platforms.value = ['win']
    loading.value = false
    return
  }
  loading.value = true
  try {
    if (!resourceId.value) throw new Error('这个资源链接暂时无法打开')
    const resource = await getEditableResource(resourceId.value, request.signal)
    if (request.signal.aborted) return
    if (!hasResourceRevision(resource.revision)) throw new Error('资源信息需要刷新，请稍后重新打开')
    fillResource(resource)
  } catch (cause) {
    if (!request.signal.aborted) loadError.value = cause instanceof Error ? cause.message : '资源加载失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

function reloadLatest(): void {
  if (window.confirm('重新读取会替换当前未保存的内容，是否继续？')) void load()
}

async function submit() {
  if (busy.value || loading.value || conflict.value) return
  error.value = ''
  if (!gameId.value || !types.value.length || !languages.value.length || !platforms.value.length) {
    error.value = '请选择游戏及每类资源属性'
    return
  }
  if (!form.name.trim() || !form.fileSize.trim()) {
    error.value = '请填写资源名称和文件大小'
    return
  }
  const url = parseResourceUrl(form.downloadUrl)
  if (!url) {
    error.value = '请填写完整、可访问的下载链接'
    return
  }
  if (editing.value && !hasResourceRevision(editable.value?.revision)) {
    error.value = '资源信息需要刷新，请重新打开后再保存'
    return
  }
  if ([types.value, languages.value, platforms.value].some(values => values.join(',').length > 100)) {
    error.value = '选择的资源属性过多，请适当减少'
    return
  }
  busy.value = true
  const context = viewVersion
  try {
    const body = {
      ...form,
      name: editing.value && form.name === editable.value?.name ? form.name : form.name.trim(),
      downloadUrl: editing.value && form.downloadUrl === editable.value?.downloadUrl ? form.downloadUrl : url.href,
      gameId: gameId.value,
      types: types.value.join(','),
      languages: languages.value.join(','),
      platforms: platforms.value.join(','),
    }
    if (editing.value) {
      const result = await editResource(resourceId.value, { ...body, revision: editable.value!.revision })
      if (context !== viewVersion) return
      fillResource(result)
    } else {
      await publishResource(body)
      if (context !== viewVersion) return
    }
    success.value = true
  } catch (cause) {
    if (context !== viewVersion) return
    // 冲突后保留草稿并阻止重放，用户明确读取新内容后才能再次保存。
    conflict.value = isResourceConflict(cause)
    error.value = conflict.value ? '资源已被更新。请重新读取最新内容，核对后再保存。' : cause instanceof Error ? cause.message : '提交失败'
  } finally {
    if (context === viewVersion) busy.value = false
  }
}
watch([editing, resourceId, () => route.query.game], () => void load(), { immediate: true })
watch(success, (saved) => { if (saved) successHeading.value?.focus() }, { flush: 'post' })
onBeforeUnmount(() => { viewVersion++; controller?.abort() })
</script>
<template>
  <div class="container section workspace-page resource-editor">
    <header class="resource-editor__heading">
      <RouterLink class="resource-editor__back" to="/profile?tab=resources"><ArrowLeft :size="16" aria-hidden="true" />我的资源</RouterLink>
      <div>
        <span class="resource-editor__icon" aria-hidden="true"><FileArchive :size="27" /></span>
        <h1>{{ editing ? '编辑游戏资源' : '分享游戏资源' }}</h1>
      </div>
      <p>{{ editing ? '核对资源的下载方式与适用范围后保存。' : '为作品补充下载信息，方便同好找到适合自己的版本。' }}</p>
    </header>
    <div v-if="!canPublish" class="work-card">
      <p>发布资源需要创作者或管理员权限。</p>
      <BaseButton to="/profile">返回个人主页</BaseButton>
    </div>
    <div v-else-if="loading" class="work-card" role="status">正在读取资源…</div>
    <div v-else-if="loadError" class="work-card">
      <p class="work-error" role="alert">{{ loadError }}</p>
      <BaseButton variant="secondary" @click="load">重新加载</BaseButton>
    </div>
    <div v-else-if="success" class="work-card resource-editor__success" role="status">
      <span class="resource-editor__confirmed"><CheckCircle2 :size="33" aria-hidden="true" /></span>
      <h2 ref="successHeading" tabindex="-1">{{ editing ? '资源已保存' : '资源已提交' }}</h2>
      <p>{{ editing ? editable?.status === 2 ? '资源正在等待审核，可在个人主页查看进度。' : '资源内容已保存，可在个人主页查看。' : '可在个人主页查看资源状态。创作者提交进入待审核流程。' }}</p>
      <div class="work-toolbar">
        <BaseButton to="/profile?tab=resources">查看我的资源</BaseButton>
        <BaseButton variant="secondary" @click="editing ? success = false : load()">{{ editing ? '继续编辑' : '继续发布' }}</BaseButton>
      </div>
    </div>
    <form v-else class="resource-editor__layout" :aria-busy="busy" @submit.prevent="submit">
      <fieldset :disabled="busy" class="resource-editor__fields">
        <legend class="sr-only">资源信息</legend>
        <section class="work-card work-form resource-editor__card" aria-labelledby="resource-basics">
          <h2 id="resource-basics"><BookOpen :size="19" aria-hidden="true" />作品与说明</h2>
          <div v-if="editing && editable" class="resource-editor__game">
            <p><span>所属游戏</span> <RouterLink :to="gameRoute(editable.gameId)">{{ editable.gameName || '查看所属作品' }}</RouterLink><span class="work-badge">{{ resourceStatusLabel(editable.status) }}</span></p>
            <p v-if="editable.reviewReason" class="work-muted work-text">最近审核说明：{{ editable.reviewReason }}</p>
          </div>
          <PublicGamePicker v-else :key="String(route.query.game || '')" v-model="gameId" />
          <label class="resource-field">
            <span>资源名称</span>
            <input v-model="form.name" required maxlength="200" placeholder="例如 简体中文版 · 含补丁" />
          </label>
          <label class="resource-field">
            <span>说明 <small>选填</small></span>
            <textarea v-model="form.remark" maxlength="500" rows="4" placeholder="写下版本差异、安装方法或游玩前要注意的事项。" />
            <small class="resource-field__count">{{ form.remark.length }} / 500</small>
          </label>
        </section>

        <section class="work-card work-form resource-editor__card" aria-labelledby="resource-download">
          <h2 id="resource-download"><Link2 :size="19" aria-hidden="true" />下载信息</h2>
          <label class="resource-field">
            <span>下载链接</span>
            <input v-model="form.downloadUrl" required type="url" inputmode="url" maxlength="500" placeholder="https://" />
          </label>
          <div class="resource-editor__credentials">
            <label class="resource-field">
              <span>提取码 <small>选填</small></span>
              <input v-model="form.extractCode" maxlength="50" autocomplete="off" />
            </label>
            <label class="resource-field">
              <span>解压密码 <small>选填</small></span>
              <input v-model="form.password" maxlength="100" autocomplete="off" />
            </label>
            <label class="resource-field">
              <span>文件大小</span>
              <input v-model="form.fileSize" required maxlength="50" placeholder="例如 4.2GB" />
            </label>
          </div>
        </section>

        <section class="work-card work-form resource-editor__card" aria-labelledby="resource-compatibility">
          <h2 id="resource-compatibility"><SlidersHorizontal :size="19" aria-hidden="true" />适用范围</h2>
          <fieldset class="resource-options">
            <legend>资源类型</legend>
            <div><label v-for="value in typeOptions" :key="value" class="work-check"><input v-model="types" type="checkbox" :value="value" /><span>{{ value === 'pc' ? 'PC' : value }}</span></label></div>
          </fieldset>
          <fieldset class="resource-options">
            <legend>语言</legend>
            <div><label v-for="value in languageOptions" :key="value" class="work-check"><input v-model="languages" type="checkbox" :value="value" /><span>{{ value }}</span></label></div>
          </fieldset>
          <fieldset class="resource-options">
            <legend>平台</legend>
            <div><label v-for="value in platformOptions" :key="value" class="work-check"><input v-model="platforms" type="checkbox" :value="value" /><span>{{ getPlatformLabel(value) }}</span></label></div>
          </fieldset>
        </section>
      </fieldset>

      <aside class="work-card resource-editor__submit">
        <nav class="resource-editor__sections" aria-label="表单分区">
          <div><strong>填写进度</strong><span>{{ filledSections }}/{{ sections.length }}</span></div>
          <progress aria-label="表单已填写的分区" :value="filledSections" :max="sections.length" />
          <a v-for="section in sections" :key="section.id" :href="`#${section.id}`" :class="{ 'resource-editor__section--ready': section.ready }">
            <component :is="section.icon" :size="17" aria-hidden="true" />{{ section.label }}
            <CheckCircle2 v-if="section.ready" :size="16" aria-hidden="true" /><span v-if="section.ready" class="sr-only">已填写</span>
          </a>
        </nav>
        <h2>{{ editing ? '保存前核对' : '提交前核对' }}</h2>
        <p class="work-muted">确认下载链接能打开，提取码和解压密码正确。</p>
        <p v-if="!auth.isAdmin" class="resource-editor__notice">{{ editing ? '修改资源内容后会重新审核。' : '提交后进入待审核，可在「我的资源」查看进度。' }}</p>
        <p v-if="error" class="work-error" role="alert">{{ error }}</p>
        <BaseButton type="submit" :loading="busy" :disabled="conflict"><Send v-if="!busy" :size="16" aria-hidden="true" />{{ editing ? '保存修改' : '确认提交资源' }}</BaseButton>
        <BaseButton v-if="conflict" variant="secondary" @click="reloadLatest">重新读取最新内容</BaseButton>
      </aside>
    </form>
  </div>
</template>

<style scoped>
.resource-editor { max-width: 1120px; }
.resource-editor__heading { padding: clamp(1rem, 3.3vw, 2.2rem); border: 1px solid var(--color-border); border-radius: var(--radius-xl); background: linear-gradient(115deg, var(--color-surface), var(--color-brand-50) 74%, var(--color-surface-soft)); }
.resource-editor__heading > div { display: flex; align-items: center; gap: .85rem; margin-top: .8rem; }
.resource-editor__heading h1, .resource-editor__success h2 { margin: 0; }
.resource-editor__heading > p { margin: .65rem 0 0; }
.resource-editor__back { display: inline-flex; align-items: center; gap: .4rem; color: var(--color-brand-700); font-size: var(--text-sm); font-weight: 700; text-decoration: none; }
.resource-editor__back:hover { text-decoration: underline; }
.resource-editor__icon { display: grid; width: 3rem; height: 3rem; flex: none; place-items: center; border-radius: var(--radius-md); background: var(--color-brand-100); color: var(--color-brand-800); }
.resource-editor__layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(15rem, .34fr); align-items: start; gap: 1.25rem; }
.resource-editor__fields { display: grid; min-width: 0; gap: 1.15rem; margin: 0; padding: 0; border: 0; }
.resource-editor__card { transition: border-color var(--motion-fast), box-shadow var(--motion-fast); }
.resource-editor__card:focus-within, .resource-editor__card:has(h2:target) { border-color: var(--color-brand-300); box-shadow: var(--shadow-md); }
.resource-editor__card h2 { display: flex; align-items: center; gap: .65rem; margin: 0 0 .2rem; scroll-margin-top: 6.7rem; }
.resource-editor__card h2 svg { box-sizing: content-box; flex: none; padding: .45rem; border-radius: var(--radius-sm); background: var(--color-brand-50); color: var(--color-brand-700); }
.resource-editor__game { padding: .7rem .9rem; border-radius: var(--radius-md); background: var(--color-surface-soft); }
.resource-editor__game > p { margin: 0; }
.resource-editor__game > p:first-child { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; font-size: var(--text-sm); }
.resource-editor__game a { color: var(--color-brand-700); font-weight: 700; }
.resource-editor__game .work-muted { margin-top: .5rem; }
.resource-field > span { color: var(--color-text-primary); font-weight: 700; }
.resource-field small { color: var(--color-text-muted); font-size: var(--text-xs); font-weight: 400; }
.resource-field > span small { margin-left: .3rem; }
.resource-field__count { justify-self: end; }
.resource-editor__credentials { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .85rem; }
.resource-options { min-width: 0; margin: 0; padding: 0; border: 0; }
.resource-options legend { margin-bottom: .45rem; padding: 0; color: var(--color-text-primary); font-size: var(--text-sm); font-weight: 700; }
.resource-options > div { display: flex; flex-wrap: wrap; gap: .45rem; }
.resource-options .work-check { min-height: 2.55rem; padding: .35rem .65rem; border: 1px solid var(--color-border); border-radius: var(--radius-pill); color: var(--color-text-secondary); font-size: var(--text-sm); cursor: pointer; }
.resource-options .work-check:has(:checked) { border-color: var(--color-brand-300); background: var(--color-brand-50); color: var(--color-brand-800); }
.resource-options input { accent-color: var(--color-brand-700); }
.resource-editor__submit { position: sticky; top: 6rem; display: grid; gap: .8rem; background: linear-gradient(155deg, var(--color-brand-50), var(--color-surface) 11rem); }
.resource-editor__submit h2, .resource-editor__submit p { margin: 0; }
.resource-editor__sections { display: grid; gap: .45rem; padding-bottom: .8rem; border-bottom: 1px solid var(--color-border); }
.resource-editor__sections > div { display: flex; justify-content: space-between; gap: .5rem; color: var(--color-brand-800); font-size: var(--text-sm); }
.resource-editor__sections progress { width: 100%; height: .32rem; margin-bottom: .2rem; border: 0; border-radius: var(--radius-pill); background: var(--color-brand-100); color: var(--color-brand-700); }
.resource-editor__sections progress::-webkit-progress-bar { border-radius: inherit; background: var(--color-brand-100); }
.resource-editor__sections progress::-webkit-progress-value { border-radius: inherit; background: var(--color-brand-700); }
.resource-editor__sections progress::-moz-progress-bar { border-radius: inherit; background: var(--color-brand-700); }
.resource-editor__sections a { display: flex; min-height: 2.45rem; align-items: center; gap: .55rem; padding-inline: .55rem; border-radius: var(--radius-sm); color: var(--color-text-secondary); font-size: var(--text-sm); text-decoration: none; }
.resource-editor__sections a:hover { background: var(--color-brand-50); color: var(--color-brand-800); }
.resource-editor__section--ready svg:nth-of-type(2) { margin-left: auto; color: var(--color-success); }
.resource-editor__notice { padding: .7rem; border-radius: var(--radius-sm); background: var(--color-brand-50); color: var(--color-brand-800); font-size: var(--text-sm); }
.resource-editor__success { padding: clamp(1.35rem, 5vw, 3rem); background: radial-gradient(ellipse at 15% 0%, color-mix(in srgb, var(--color-success) 12%, var(--color-surface)), var(--color-surface) 55%); }
.resource-editor__confirmed { display: grid; width: 3.9rem; height: 3.9rem; place-items: center; margin-bottom: .8rem; border: 1px solid color-mix(in srgb, var(--color-success) 32%, var(--color-surface)); border-radius: 50%; background: var(--color-surface); color: var(--color-success); box-shadow: var(--shadow-sm); }
@media (prefers-reduced-motion: no-preference) {
  .resource-editor__confirmed { animation: confirm-resource 420ms cubic-bezier(.2, .8, .3, 1) both; }
  @keyframes confirm-resource { from { opacity: 0; transform: scale(.75); } 60% { transform: scale(1.08); } to { opacity: 1; transform: none; } }
}
@media (max-width: 800px) {
  .resource-editor__layout { grid-template-columns: minmax(0, 1fr); }
  .resource-editor__submit { position: static; }
}
@media (max-width: 550px) { .resource-editor__credentials { grid-template-columns: minmax(0, 1fr); } }
</style>
