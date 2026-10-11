<template>
  <input
    :value="modelValue"
    type="text"
    :placeholder="placeholder"
    :disabled="disabled"
    class="ins-input"
    @input="onInput"
  />
</template>

<script setup lang="ts">
/**
 * 字符串输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，与后端 InspectorFieldType.STRING 对应。</p>
 */

interface Props {
  /** 当前字符串值。 */
  modelValue: string
  /** 占位文本。 */
  placeholder?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '',
  disabled: false,
})

const emit = defineEmits<{
  /** 字符串值变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 处理 input 事件。
 */
function onInput(event: Event): void {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}
</script>

<style scoped>
.ins-input {
  width: 100%;
  padding: 2px;
  font-size: 13px;
  color: var(--editor-text);
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  outline: none;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.ins-input:hover {
  background: var(--editor-surface);
}

.ins-input:focus {
  background: var(--editor-surface);
  border-color: var(--editor-accent);
}

.ins-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--editor-surface-light);
}

.ins-input::placeholder {
  color: var(--editor-text-dim);
}
</style>
