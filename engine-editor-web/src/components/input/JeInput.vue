<template>
  <input
    :type="type"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    class="je-input"
    @input="onInput"
  />
</template>

<script setup lang="ts">
/**
 * 自定义文本输入框组件。
 *
 * <p>支持 v-model 绑定，提供统一的主题样式。</p>
 */

interface Props {
  /** 输入框当前值。 */
  modelValue: string
  /** 占位文本。 */
  placeholder?: string
  /** 输入框类型，默认为 text。 */
  type?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  type: 'text',
  placeholder: '',
  disabled: false,
})

const emit = defineEmits<{
  /** 输入内容变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 处理 input 事件。
 *
 * @param event 输入事件
 */
function onInput(event: Event): void {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}
</script>

<style scoped>
.je-input {
  width: 100%;
  padding: 4px 8px;
  font-size: 13px;
  color: var(--editor-text);
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  outline: none;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.je-input:hover {
  background: var(--editor-surface);
}

.je-input:focus {
  background: var(--editor-surface);
  border-color: var(--editor-accent);
}

.je-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--editor-surface-light);
}

.je-input::placeholder {
  color: var(--editor-text-dim);
}
</style>
