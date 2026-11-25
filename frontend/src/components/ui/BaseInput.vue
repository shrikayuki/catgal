<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    label: string
    name?: string
    type?: 'text' | 'search' | 'email' | 'password'
    placeholder?: string
    hint?: string
    error?: string
    autocomplete?: string
    disabled?: boolean
  }>(),
  {
    name: undefined,
    type: 'text',
    placeholder: undefined,
    hint: undefined,
    error: undefined,
    autocomplete: undefined,
    disabled: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const generatedId = useId()
const inputId = computed(() => props.name || generatedId)
const descriptionId = computed(() => `${inputId.value}-description`)
</script>

<template>
  <label class="field" :for="inputId">
    <span class="field__label">{{ label }}</span>
    <input
      :id="inputId"
      class="field__input"
      :class="{ 'field__input--error': error }"
      :name="name"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :autocomplete="autocomplete"
      :disabled="disabled"
      :aria-invalid="Boolean(error)"
      :aria-describedby="hint || error ? descriptionId : undefined"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <span v-if="error || hint" :id="descriptionId" class="field__message" :class="{ 'field__message--error': error }">
      {{ error || hint }}
    </span>
  </label>
</template>

<style scoped>
.field {
  display: grid;
  gap: 0.45rem;
}

.field__label {
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.field__input {
  width: 100%;
  min-height: 3rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--color-surface-soft) 35%, var(--color-surface));
  color: var(--color-text-primary);
  padding: 0.75rem 0.9rem;
  transition:
    border-color var(--motion-fast),
    box-shadow var(--motion-fast);
}

.field__input::placeholder {
  color: var(--color-text-muted);
}

.field__input:hover:not(:disabled, :focus) { border-color: var(--color-border-strong); }

.field__input:focus {
  border-color: var(--color-brand-500);
  background: var(--color-surface);
  box-shadow: 0 0 0 3px var(--color-focus);
  outline: 0;
}

.field__input:disabled { opacity: .72; cursor: not-allowed; }

.field__input--error {
  border-color: var(--color-danger);
}

.field__message {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.field__message--error {
  color: var(--color-danger);
}
</style>
