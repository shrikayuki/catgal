<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { addReview } from '../../api/community'
import BaseButton from '../ui/BaseButton.vue'
const props = defineProps<{ gameId: number }>()
const emit = defineEmits<{ saved: [] }>()
const auth = useAuthStore(),
  route = useRoute()
const form = reactive({
  score: 8,
  recommendLevel: 4,
  playStatus: 1,
  spoilerLevel: 1,
  briefReview: '',
})
const busy = ref(false),
  error = ref('')
async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await addReview({ gameId: props.gameId, ...form })
    form.briefReview = ''
    emit('saved')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '评价提交失败'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <form class="work-card workspace-page work-form" @submit.prevent="save">
    <template v-if="auth.isLoggedIn">
      <h2>写下你的评价</h2>
      <div class="work-grid">
        <label>
          评分
          <input v-model.number="form.score" type="number" min="1" max="10" required />
        </label>
        <label>
          推荐程度
          <select v-model.number="form.recommendLevel" aria-label="推荐程度">
            <option :value="1">强烈不推荐</option>
            <option :value="2">不推荐</option>
            <option :value="3">中立</option>
            <option :value="4">推荐</option>
            <option :value="5">强烈推荐</option>
          </select>
        </label>
        <label>
          游玩状态
          <select v-model.number="form.playStatus" aria-label="游玩状态">
            <option
              v-for="(label, index) in ['未开始', '正在通关', '单线', '主线', '全线', '弃坑']"
              :key="label"
              :value="index + 1"
            >
              {{ label }}
            </option>
          </select>
        </label>
        <label>
          剧透程度
          <select v-model.number="form.spoilerLevel" aria-label="剧透程度">
            <option :value="1">无剧透</option>
            <option :value="2">轻微剧透</option>
            <option :value="3">严重剧透</option>
          </select>
        </label>
      </div>
      <label>
        简评
        <textarea
          v-model="form.briefReview"
          maxlength="520"
          rows="3"
          placeholder="谈谈作品给你留下的印象。"
        />
      </label>
      <p v-if="error" class="work-error" role="alert">{{ error }}</p>
      <div><BaseButton type="submit" :loading="busy">发布评价</BaseButton></div>
    </template>
    <template v-else>
      <p class="work-muted">登录后分享你的游玩评价。</p>
      <BaseButton :to="{ name: 'login', query: { redirect: route.fullPath } }" size="small">
        登录后评价
      </BaseButton>
    </template>
  </form>
</template>
