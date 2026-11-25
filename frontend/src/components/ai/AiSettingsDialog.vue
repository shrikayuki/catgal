<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import {
  createAiCredential,
  deleteAiBot,
  getAiBot,
  getAiProviders,
  getAiTemplates,
  listAiCredentials,
  listAiPrompts,
  revokeAiCredential,
  rotateAiCredential,
  saveAiBot,
  uploadAiImage,
  clearAiImage,
  type AiImageKind,
  type AiImageUpdate,
} from '../../api/ai'
import type { AiBot, AiCredential, AiPromptVersion, AiTemplate, BotInput } from '../../types/ai'
import ActionDialog from '../ui/ActionDialog.vue'
import BaseButton from '../ui/BaseButton.vue'
import AiImagePicker from './AiImagePicker.vue'

const props = defineProps<{ bots: AiBot[]; selectedBotId: string | null }>()
const emit = defineEmits<{ close: []; changed: []; selected: [bot: AiBot] }>()
const tab = ref<'bot' | 'keys'>('bot')
const templates = ref<AiTemplate[]>([]),
  providers = ref<{ code: string }[]>([]),
  credentials = ref<AiCredential[]>([])
const credentialCursor = ref<string | null>(null),
  busy = ref(false),
  loading = ref(false),
  error = ref(''),
  success = ref('')
const selected = ref<AiBot | null>(null),
  editorId = ref<string | null>(props.selectedBotId)
const form = reactive<BotInput>({
  girlCode: '',
  name: '',
  persona: '',
  avatarUrl: '',
  portraitUrl: '',
  credentialId: null,
  model: 'qwen-plus',
})
const keyForm = reactive({ providerCode: '', label: '', apiKey: '' })
const imageFiles = reactive<Record<AiImageKind, File | null>>({ avatar: null, portrait: null })
const removedImages = reactive({ avatar: false, portrait: false })
const rotate = ref<AiCredential | null>(null)
const versions = ref<AiPromptVersion[]>([]),
  versionCursor = ref<string | null>(null)
const isPlatform = computed(() => selected.value?.kind === 'platform')
const platformBots = computed(() => props.bots.filter((bot) => bot.kind === 'platform'))
const personalBots = computed(() => props.bots.filter((bot) => bot.kind === 'custom'))
const hasActiveCredential = computed(() => credentials.value.some((row) => row.status === 1))
const controller = new AbortController()
let editSequence = 0,
  disposed = false
function message(cause: unknown) {
  return cause instanceof Error ? cause.message : '操作失败，请刷新后重试'
}
async function loadCredentials(more = false) {
  const page = await listAiCredentials(more ? credentialCursor.value : null, controller.signal)
  if (disposed) return
  credentials.value = more ? [...credentials.value, ...page.items] : page.items
  credentialCursor.value = page.nextCursor
}
async function edit(id: string | null) {
  const sequence = ++editSequence
  editorId.value = id
  selected.value = null
  versions.value = []
  versionCursor.value = null
  error.value = ''
  success.value = ''
  imageFiles.avatar = imageFiles.portrait = null
  removedImages.avatar = removedImages.portrait = false
  Object.assign(form, {
    girlCode: templates.value[0]?.girlCode ?? 'catgal-assistant',
    name: '',
    persona: '',
    avatarUrl: '',
    portraitUrl: '',
    credentialId: credentials.value.find((row) => row.status === 1)?.id ?? null,
    model: 'qwen-plus',
    revision: undefined,
  })
  if (!id) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const bot = await getAiBot(id, controller.signal)
    if (disposed || sequence !== editSequence) return
    selected.value = bot
    Object.assign(form, {
      name: bot.name,
      persona: bot.persona ?? '',
      avatarUrl: bot.avatarUrl,
      portraitUrl: bot.portraitUrl,
      credentialId: bot.credentialId,
      model: bot.model || 'qwen-plus',
      revision: bot.revision,
    })
  } catch (cause) {
    if (!disposed && sequence === editSequence) error.value = message(cause)
  } finally {
    if (!disposed && sequence === editSequence) loading.value = false
  }
}
async function initialize() {
  loading.value = true
  try {
    const [templateData, providerData] = await Promise.all([
      getAiTemplates(controller.signal),
      getAiProviders(controller.signal),
      loadCredentials(),
    ])
    if (disposed) return
    templates.value = templateData.items
    providers.value = providerData.items
    keyForm.providerCode = providerData.items[0]?.code ?? ''
    await edit(props.selectedBotId)
  } catch (cause) {
    if (!disposed) error.value = message(cause)
  } finally {
    if (!disposed) loading.value = false
  }
}
async function save() {
  if (busy.value || loading.value) return
  busy.value = true
  error.value = ''
  success.value = ''
  let saved = false
  try {
    let result = await saveAiBot(selected.value?.id ?? null, {
      ...form,
      name: form.name.trim(),
      model: form.model.trim(),
    }, controller.signal)
    if (disposed) return
    selected.value = result
    editorId.value = result.id
    form.revision = result.revision
    saved = true
    // 保留已创建的伙伴及每张已保存图片，失败重试时从当前进度继续。
    for (const kind of ['avatar', 'portrait'] as const) {
      const file = imageFiles[kind]
      let update: AiImageUpdate | undefined
      if (file) update = await uploadAiImage(result.id, kind, result.revision, file, controller.signal)
      else if (removedImages[kind]) update = await clearAiImage(result.id, kind, result.revision, controller.signal)
      if (disposed) return
      if (!update) continue
      const field = kind === 'avatar' ? 'avatarUrl' : 'portraitUrl'
      result = { ...result, [field]: update.url, revision: update.revision }
      selected.value = result
      form[field] = update.url
      form.revision = update.revision
      imageFiles[kind] = null
      removedImages[kind] = false
    }
    success.value = '机器人已保存'
    emit('selected', result)
  } catch (cause) {
    if (!disposed) error.value = saved ? `资料已保存，图片尚未全部保存。${message(cause)}` : message(cause)
  } finally {
    if (saved && !disposed) emit('changed')
    if (!disposed) busy.value = false
  }
}

function chooseImage(kind: AiImageKind, file: File | null) {
  imageFiles[kind] = file
  if (file) removedImages[kind] = false
}

function removeImage(kind: AiImageKind) {
  imageFiles[kind] = null
  removedImages[kind] = true
}
async function archive() {
  if (
    !selected.value ||
    busy.value ||
    !window.confirm(`归档「${selected.value.name}」？已有会话记录会保留。`)
  )
    return
  busy.value = true
  error.value = ''
  try {
    await deleteAiBot(selected.value)
    if (!disposed) {
      emit('changed')
      await edit(null)
      success.value = '机器人已归档'
    }
  } catch (cause) {
    if (!disposed) error.value = message(cause)
  } finally {
    if (!disposed) busy.value = false
  }
}
async function saveKey() {
  if (busy.value) return
  const secret = keyForm.apiKey.trim()
  keyForm.apiKey = ''
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const result = rotate.value
      ? await rotateAiCredential(rotate.value, secret)
      : await createAiCredential(keyForm.providerCode, keyForm.label.trim(), secret)
    if (disposed) return
    rotate.value = null
    keyForm.label = ''
    await loadCredentials()
    if (!form.credentialId) form.credentialId = result.id
    success.value = 'API Key 已加密保存，返回“机器人配置”即可绑定使用'
    emit('changed')
  } catch (cause) {
    if (!disposed) error.value = message(cause)
  } finally {
    keyForm.apiKey = ''
    if (!disposed) busy.value = false
  }
}
function startRotation(row: AiCredential) {
  rotate.value = row
  keyForm.apiKey = ''
  success.value = ''
}
function cancelRotation() {
  rotate.value = null
  keyForm.apiKey = ''
}
async function revoke(row: AiCredential) {
  if (
    busy.value ||
    !window.confirm(`撤销「${row.label}」？绑定它的机器人将需要更新凭据才能继续生成。`)
  )
    return
  busy.value = true
  error.value = ''
  try {
    await revokeAiCredential(row)
    if (!disposed) {
      await loadCredentials()
      emit('changed')
      success.value = '凭据已撤销'
    }
  } catch (cause) {
    if (!disposed) error.value = message(cause)
  } finally {
    if (!disposed) busy.value = false
  }
}
async function prompts(more = false) {
  if (!selected.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await listAiPrompts(selected.value.id, more ? versionCursor.value : null)
    if (!disposed) {
      versions.value = more ? [...versions.value, ...result.items] : result.items
      versionCursor.value = result.nextCursor
    }
  } catch (cause) {
    if (!disposed) error.value = message(cause)
  } finally {
    if (!disposed) busy.value = false
  }
}
async function moreKeys() {
  if (busy.value) return
  busy.value = true
  try {
    await loadCredentials(true)
  } catch (cause) {
    if (!disposed) error.value = message(cause)
  } finally {
    if (!disposed) busy.value = false
  }
}
onBeforeUnmount(() => {
  disposed = true
  controller.abort()
  keyForm.apiKey = ''
})
void initialize()
</script>

<template>
  <ActionDialog title="机器人设置与 API Key" @close="emit('close')">
    <nav class="work-toolbar" aria-label="AI 设置分类">
      <BaseButton :variant="tab === 'bot' ? 'primary' : 'ghost'" @click="tab = 'bot'"
        >机器人配置</BaseButton
      ><BaseButton :variant="tab === 'keys' ? 'primary' : 'ghost'" @click="tab = 'keys'"
        >我的 API Key</BaseButton
      >
    </nav>
    <p v-if="error" class="work-error" role="alert">{{ error }}</p>
    <p v-if="success" class="work-success" role="status">{{ success }}</p>
    <p v-if="loading" class="work-muted" role="status">正在读取配置…</p>
    <section v-if="tab === 'bot'">
      <label
        >配置对象<select
          :value="editorId ?? ''"
          :disabled="busy || loading"
          @change="edit(($event.target as HTMLSelectElement).value || null)"
        >
          <option value="">＋ 创建个人机器人</option>
          <optgroup v-if="platformBots.length" label="平台助手">
            <option v-for="bot in platformBots" :key="bot.id" :value="bot.id">
              {{ bot.name }}
            </option>
          </optgroup>
          <optgroup v-if="personalBots.length" label="我的个人机器人">
            <option v-for="bot in personalBots" :key="bot.id" :value="bot.id">
              {{ bot.name }}
            </option>
          </optgroup>
        </select></label
      >
      <section v-if="!loading" class="ai-setup-guide" aria-label="机器人使用说明">
        <template v-if="isPlatform">
          <h3>平台助手 · 当前账号的使用配置</h3>
          <p>平台统一维护名称、人设和形象。这里保存你使用这个助手的配置，会话与私有记忆按账号独立。</p>
          <p>
            {{ form.credentialId
              ? '当前使用你绑定的 API Key，调用计入该 Key 对应的模型账号。'
              : '当前使用平台提供的模型，调用费用由平台承担。' }}
          </p>
          <BaseButton size="small" variant="secondary" :disabled="busy" @click="edit(null)">
            创建可自定义的个人机器人
          </BaseButton>
        </template>
        <template v-else>
          <h3>{{ selected ? '个人机器人 · 使用自己的 API Key' : '创建你的个人机器人' }}</h3>
          <p>你可以自定义名称、人设、头像和立绘。模型调用使用你绑定的 API Key，费用计入对应模型账号。</p>
          <ol v-if="!selected">
            <li>在“我的 API Key”中添加模型服务的 Key。</li>
            <li>填写机器人形象与人设，选择 Key 和模型。</li>
            <li>创建后开启新对话，私有记忆可单独设置。</li>
          </ol>
        </template>
      </section>
      <div v-if="!isPlatform && !loading && !hasActiveCredential" class="ai-key-guide" role="status">
        <p>创建个人机器人需要有效的 API Key。先添加 Key，再回来完成配置。</p>
        <BaseButton size="small" variant="secondary" :disabled="busy" @click="tab = 'keys'">
          去添加 API Key
        </BaseButton>
      </div>
      <form class="work-form ai-editor" @submit.prevent="save">
        <fieldset :disabled="busy || loading">
          <label v-if="!selected"
            >基础模板<select v-model="form.girlCode" required>
              <option v-for="template in templates" :key="template.id" :value="template.girlCode">
                {{ template.name }}
              </option>
            </select></label
          >
          <label v-if="!isPlatform">名称<input v-model="form.name" required maxlength="64" /></label>
          <label v-if="!isPlatform"
            >角色设定<textarea
              v-model="form.persona"
              rows="4"
              maxlength="4000"
              placeholder="描述希望的语气、性格和交流方式"
            />
          </label>
          <template v-if="!isPlatform">
            <AiImagePicker :model-value="imageFiles.avatar" :src="form.avatarUrl" label="头像"
              :removed="removedImages.avatar" :disabled="busy || loading"
              @update:model-value="chooseImage('avatar', $event)" @remove="removeImage('avatar')"
              @restore="removedImages.avatar = false" />
            <AiImagePicker :model-value="imageFiles.portrait" :src="form.portraitUrl" label="立绘" portrait
              :removed="removedImages.portrait" :disabled="busy || loading"
              @update:model-value="chooseImage('portrait', $event)" @remove="removeImage('portrait')"
              @restore="removedImages.portrait = false" />
          </template>
          <label
            >{{ isPlatform ? '模型调用来源' : '使用的 API Key' }}<select
              v-model="form.credentialId"
              :required="!isPlatform"
            >
              <option v-if="isPlatform" :value="null">平台提供模型 · 平台承担调用费用</option>
              <option v-else :value="null" disabled>请先添加并选择自己的 API Key</option>
              <option
                v-if="form.credentialId && !credentials.some((row) => row.id === form.credentialId)"
                :value="form.credentialId"
              >
                已绑定保存的密钥，可加载更多查看详情
              </option>
              <option
                v-for="row in credentials"
                :key="row.id"
                :value="row.id"
                :disabled="row.status !== 1"
              >
                我的 Key · {{ row.label }} · {{ row.providerCode }} · {{ row.maskedKey
                }}{{ row.status === 1 ? '' : ' · 已撤销' }}
              </option>
            </select></label
          >
          <p v-if="isPlatform" class="work-muted">
            模型来源修改在保存后生效。可选用自己的 API Key，费用随所选 Key 归属。选择“平台提供模型”可恢复平台默认调用。
          </p>
          <label
            >模型名称<input
              v-model="form.model"
              required
              maxlength="100"
              pattern="[A-Za-z0-9][A-Za-z0-9._:\/\-]{0,99}"
              placeholder="例如 qwen-plus"
              :disabled="isPlatform && !form.credentialId"
          /></label>
          <div class="work-toolbar">
            <BaseButton
              type="submit"
              :loading="busy"
              :disabled="!isPlatform && !form.credentialId"
              >{{ selected ? '保存配置' : '创建机器人' }}</BaseButton
            ><BaseButton v-if="selected" variant="ghost" @click="edit(selected.id)"
              >重新读取</BaseButton
            ><button
              v-if="selected?.kind === 'custom'"
              class="work-danger"
              type="button"
              @click="archive"
            >
              归档机器人
            </button>
          </div>
        </fieldset>
      </form>
      <BaseButton
        v-if="credentialCursor"
        size="small"
        variant="ghost"
        :disabled="busy"
        @click="moreKeys"
        >加载更多模型凭据</BaseButton
      >
      <section v-if="selected" class="ai-version-list">
        <BaseButton size="small" variant="ghost" :disabled="busy" @click="prompts()"
          >查看人设版本</BaseButton
        >
        <details v-for="version in versions" :key="version.id">
          <summary>版本 {{ version.version }}</summary>
          <p>{{ version.persona || '平台基础人设' }}</p>
        </details>
        <BaseButton
          v-if="versionCursor"
          size="small"
          variant="ghost"
          :disabled="busy"
          @click="prompts(true)"
          >更早版本</BaseButton
        >
      </section>
    </section>
    <section v-else>
      <div class="ai-setup-guide">
        <h3>你的模型服务 API Key</h3>
        <p>创建个人机器人前，先添加你从模型服务商获取的 Key，再到“机器人配置”中绑定。调用费用计入该 Key 对应的模型账号。</p>
        <p>平台默认模型可以直接使用。你添加的 API Key 会加密保存，列表中展示名称和末四位，方便辨认。</p>
      </div>
      <form class="work-form" @submit.prevent="saveKey">
        <h3>{{ rotate ? `轮换「${rotate.label}」` : '添加我的 API Key' }}</h3>
        <label v-if="!rotate"
          >模型来源<select v-model="keyForm.providerCode" required :disabled="busy">
            <option v-for="provider in providers" :key="provider.code" :value="provider.code">
              {{ provider.code }}
            </option>
          </select></label
        >
        <label v-if="!rotate"
          >凭据名称<input
            v-model="keyForm.label"
            required
            maxlength="64"
            :disabled="busy"
            placeholder="例如 我的日常聊天 Key"
        /></label>
        <label
          >API Key<input
            v-model="keyForm.apiKey"
            type="password"
            required
            minlength="8"
            maxlength="1024"
            autocomplete="new-password"
            spellcheck="false"
            :disabled="busy"
        /></label>
        <div class="work-toolbar">
          <BaseButton type="submit" :loading="busy">{{
            rotate ? '确认轮换' : '加密保存'
          }}</BaseButton
          ><BaseButton v-if="rotate" variant="ghost" :disabled="busy" @click="cancelRotation"
            >取消轮换</BaseButton
          ><BaseButton v-else variant="ghost" :disabled="busy" @click="tab = 'bot'"
            >返回机器人配置</BaseButton
          >
        </div>
      </form>
      <ul class="ai-key-list">
        <li v-for="row in credentials" :key="row.id">
          <div>
            <strong>{{ row.label }}</strong>
            <p class="work-muted">
              {{ row.providerCode }} · {{ row.maskedKey }} ·
              {{ row.status === 1 ? '有效' : '已撤销' }}
            </p>
          </div>
          <div v-if="row.status === 1" class="work-toolbar">
            <BaseButton
              size="small"
              variant="secondary"
              :disabled="busy"
              @click="startRotation(row)"
              >轮换</BaseButton
            ><button type="button" class="work-danger" :disabled="busy" @click="revoke(row)">
              撤销
            </button>
          </div>
        </li>
      </ul>
      <p v-if="!credentials.length && !loading" class="work-muted">尚未添加模型凭据。</p>
      <BaseButton
        v-if="credentialCursor"
        size="small"
        variant="ghost"
        :disabled="busy"
        @click="moreKeys"
        >加载更多</BaseButton
      >
    </section>
  </ActionDialog>
</template>

<style scoped>
.ai-setup-guide,
.ai-key-guide {
  margin-top: 1rem;
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-brand-50);
  font-size: var(--text-sm);
  line-height: 1.7;
}
.ai-setup-guide h3 {
  margin: 0;
  font-size: var(--text-base);
}
.ai-setup-guide p,
.ai-key-guide p {
  margin: 0.6rem 0;
}
.ai-setup-guide ol {
  padding-left: 1.25rem;
  margin: 0.6rem 0 0;
}
.ai-editor {
  margin-top: 1rem;
}
fieldset {
  display: grid;
  gap: 1rem;
  padding: 0;
  border: 0;
  min-width: 0;
}
.ai-key-list {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 0.75rem;
}
.ai-key-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.ai-key-list p {
  margin: 0.4rem 0 0;
}
.ai-version-list {
  margin-top: 1rem;
}
.ai-version-list details {
  padding: 0.75rem;
  border-bottom: 1px solid var(--color-border);
}
.ai-version-list p {
  white-space: pre-wrap;
}
</style>
