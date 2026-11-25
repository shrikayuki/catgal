<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Copy, ExternalLink, FolderKey, RefreshCw, X } from 'lucide-vue-next'
import { getResourceAccess, requestResourceDownload, type ResourceAccess } from '../../api/resources'
import { parseResourceUrl } from '../../utils/resource-url'
import { useNotificationStore } from '../../stores/notifications'
import BaseButton from '../ui/BaseButton.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{ resourceId: number; resourceName: string }>()
const emit = defineEmits<{ close: []; downloaded: [] }>()
const notifications = useNotificationStore()
const dialog = ref<HTMLDialogElement | null>(null)
const access = ref<ResourceAccess | null>(null)
const loading = ref(false)
const downloading = ref(false)
const error = ref('')
const downloadError = ref('')
const readyUrl = ref<URL | null>(null)
const manualOpen = ref(false)
const downloadRecorded = ref(false)
const sourceUrl = computed(() => parseResourceUrl(access.value?.downloadUrl))
let controller: AbortController | undefined
let pendingTab: Window | null = null
let closeEmitted = false

async function load() {
  if (loading.value || downloading.value) return
  controller?.abort()
  const request = new AbortController()
  controller = request
  access.value = null
  readyUrl.value = null
  error.value = ''
  downloadError.value = ''
  loading.value = true
  try {
    const response = await getResourceAccess(props.resourceId, request.signal)
    if (!request.signal.aborted) access.value = response
  } catch (cause) {
    if (!request.signal.aborted) error.value = cause instanceof Error ? cause.message : '资源读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

async function copy(value: string, label: string) {
  try {
    await navigator.clipboard.writeText(value)
    notifications.show(`${label}已复制`, 'success')
  } catch {
    notifications.show('复制失败，请选中文字后手动复制', 'warning')
  }
}

async function openDownload() {
  if (downloading.value || downloadRecorded.value || !sourceUrl.value) return
  downloadError.value = ''
  manualOpen.value = false
  downloading.value = true
  const request = new AbortController()
  controller = request
  try {
    // 在用户点击的同步阶段预开标签页，等待接口通过后才打开服务器返回的地址。
    pendingTab = window.open('about:blank', '_blank')
    if (pendingTab) {
      pendingTab.opener = null
      pendingTab.document.title = '打开下载链接'
      pendingTab.document.body.textContent = '正在打开下载链接…'
    }

    const result = await requestResourceDownload(props.resourceId, request.signal)
    if (request.signal.aborted) return
    downloadRecorded.value = true
    emit('downloaded')
    readyUrl.value = parseResourceUrl(result)
    if (!readyUrl.value) {
      downloadError.value = '下载入口格式无效，请联系发布者核对链接'
      return
    }
    if (!pendingTab || pendingTab.closed) {
      manualOpen.value = true
      return
    }

    const link = pendingTab.document.createElement('a')
    link.href = readyUrl.value.href
    link.rel = 'noreferrer'
    pendingTab.document.body.append(link)
    link.click()
    pendingTab = null
  } catch (cause) {
    if (request.signal.aborted) return
    if (readyUrl.value) {
      manualOpen.value = true
    } else {
      downloadError.value = cause instanceof Error ? cause.message : '下载请求失败，请稍后重试'
    }
  } finally {
    pendingTab?.close()
    pendingTab = null
    if (!request.signal.aborted) downloading.value = false
  }
}

function cancelPending() {
  controller?.abort()
  pendingTab?.close()
  pendingTab = null
}

function close() {
  if (!dialog.value?.open) return
  // 点击关闭或按 Escape 后立即清理地址栏，不依赖浏览器稍后的 close 事件。
  dialog.value.close()
  dialogClosed()
}

function dialogClosed() {
  if (closeEmitted) return
  closeEmitted = true
  cancelPending()
  emit('close')
}

function backdropClick(event: MouseEvent) {
  if (event.target !== dialog.value) return
  const bounds = dialog.value?.getBoundingClientRect()
  if (bounds && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) close()
}
onMounted(() => { dialog.value?.showModal(); void load() })
onBeforeUnmount(() => {
  closeEmitted = true
  cancelPending()
  access.value = null
  readyUrl.value = null
  dialog.value?.close()
})
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="work-dialog resource-dialog" aria-labelledby="resource-dialog-title" @cancel.prevent="close" @close="dialogClosed" @click="backdropClick">
      <header class="resource-dialog__header">
        <div><p><FolderKey :size="15" aria-hidden="true" /> 获取资源</p><h2 id="resource-dialog-title">{{ resourceName }}</h2></div>
        <button class="resource-dialog__close" type="button" aria-label="关闭资源窗口" autofocus @click="close"><X :size="20" aria-hidden="true" /></button>
      </header>
      <StatePanel v-if="loading" tone="loading" title="正在读取资源入口" description="链接信息正在加载。" />
      <StatePanel v-else-if="error" tone="error" title="暂时无法查看资源" :description="error"><BaseButton variant="secondary" @click="load"><RefreshCw :size="16" aria-hidden="true" />重新读取</BaseButton></StatePanel>
      <template v-else-if="access">
        <div class="resource-dialog__summary"><span>文件大小</span><strong>{{ access.fileSize || '发布者暂未填写' }}</strong><span v-if="sourceUrl">来源 · {{ sourceUrl.hostname }}</span></div>
        <div class="resource-dialog__fields">
          <div class="resource-dialog__link-field">
            <span>资源链接</span>
            <div>
              <a v-if="readyUrl" class="resource-dialog__url" :href="readyUrl.href" target="_blank" rel="noopener noreferrer">
                <span>{{ readyUrl.href }}</span><ExternalLink :size="16" aria-hidden="true" />
              </a>
              <button v-else-if="sourceUrl" class="resource-dialog__url" type="button" :disabled="downloading || downloadRecorded" :aria-busy="downloading" @click="openDownload">
                <span>{{ sourceUrl.href }}</span><ExternalLink :size="16" aria-hidden="true" />
              </button>
              <input v-else :value="access.downloadUrl ?? ''" readonly aria-label="资源链接" />
              <button class="resource-dialog__copy" :disabled="!sourceUrl" type="button" aria-label="复制资源链接" @click="copy((readyUrl ?? sourceUrl)!.href, '资源链接')"><Copy :size="17" aria-hidden="true" /></button>
            </div>
          </div>
          <label v-if="access.extractCode">提取码<div><input :value="access.extractCode" readonly /><button class="resource-dialog__copy" type="button" aria-label="复制提取码" @click="copy(access.extractCode!, '提取码')"><Copy :size="17" aria-hidden="true" /></button></div></label>
          <label v-if="access.password">解压密码<div><input :value="access.password" readonly /><button class="resource-dialog__copy" type="button" aria-label="复制解压密码" @click="copy(access.password!, '解压密码')"><Copy :size="17" aria-hidden="true" /></button></div></label>
        </div>
        <p v-if="!sourceUrl" class="resource-dialog__error" role="alert">发布者提供的链接格式无效，请联系发布者核对。</p>
        <p v-if="sourceUrl" class="resource-dialog__notice">点击链接在新标签页打开下载站点。<template v-if="access.extractCode || access.password">可以留在本页复制所需密码。</template></p>
        <p v-if="downloadError" class="resource-dialog__error" role="alert">{{ downloadError }}</p>
        <p v-if="downloading" role="status">正在打开下载链接…</p>
        <p v-if="manualOpen" role="status">未能自动打开新标签页，请点击上方链接。</p>
      </template>
    </dialog>
  </Teleport>
</template>

<style scoped>
.resource-dialog { width: min(42rem, calc(100vw - 2rem)); padding: clamp(1rem, 4vw, 1.75rem); }
.resource-dialog__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; padding-bottom: 1.25rem; border-bottom: 1px solid var(--color-border); }
.resource-dialog__header > div { min-width: 0; }
.resource-dialog__header p { display: flex; align-items: center; gap: .4rem; margin: 0 0 .65rem; font-size: var(--text-xs); color: var(--color-brand-600); letter-spacing: .1em; }
.resource-dialog__header h2 { margin: 0; font-family: var(--font-display); color: var(--color-brand-800); font-size: var(--text-xl); line-height: 1.5; overflow-wrap: anywhere; }
.resource-dialog__close { display: grid; flex: 0 0 auto; width: 2.3rem; height: 2.3rem; place-items: center; border: 1px solid var(--color-border); border-radius: 50%; background: var(--color-bg); color: var(--color-text-secondary); cursor: pointer; }
.resource-dialog__summary { display: flex; flex-wrap: wrap; gap: .5rem 1rem; margin-block: 1.1rem; padding: .75rem .9rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-brand-50); font-size: var(--text-sm); color: var(--color-text-secondary); }
.resource-dialog__summary strong { color: var(--color-brand-900); }
.resource-dialog__summary > * { max-width: 100%; overflow-wrap: anywhere; }
.resource-dialog__fields { display: grid; gap: 1.1rem; }
.resource-dialog__fields :is(label, .resource-dialog__link-field) { display: grid; gap: .5rem; font-size: var(--text-sm); color: var(--color-text-secondary); }
.resource-dialog__fields :is(label, .resource-dialog__link-field) > div { display: flex; min-width: 0; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg); }
.resource-dialog__fields input { width: 100%; min-width: 0; border: 0; background: transparent; color: var(--color-text-primary); padding: .8rem; }
.resource-dialog__copy { display: grid; flex: 0 0 auto; width: 2.75rem; place-items: center; border: 0; border-left: 1px solid var(--color-border); background: transparent; color: var(--color-brand-700); cursor: pointer; }
.resource-dialog__url { display: flex; flex: 1; min-width: 0; align-items: center; gap: .5rem; border: 0; padding: .8rem; background: transparent; color: var(--color-brand-700); font: inherit; text-align: left; text-decoration: underline; user-select: text; cursor: pointer; }
.resource-dialog__url span { min-width: 0; overflow-wrap: anywhere; }
.resource-dialog__url svg { flex: 0 0 auto; }
.resource-dialog__fields button:disabled { opacity: .45; cursor: not-allowed; }
.resource-dialog :focus-visible { outline: 3px solid var(--color-focus); outline-offset: 2px; }
.resource-dialog__notice { margin-block: 1.25rem; padding: 1rem; border-radius: var(--radius-sm); background: var(--color-brand-50); color: var(--color-text-secondary); line-height: 1.8; font-size: var(--text-sm); }
.resource-dialog__error { padding: .8rem; border-radius: var(--radius-sm); background: #fff4f5; color: var(--color-danger); font-size: var(--text-sm); }
@media (max-width: 479px) { .resource-dialog { padding: 1.1rem; border-radius: var(--radius-lg); } }
</style>
