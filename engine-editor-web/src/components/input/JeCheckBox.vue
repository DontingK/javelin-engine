<template>
  <label class="je-checkbox" :class="{ disabled: disabled }">
    <input
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      @change="onChange"
    />
    <span class="je-checkbox-box" aria-hidden="true">
      <svg v-if="modelValue" class="je-checkbox-check" viewBox="0 0 24 24">
        <path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
      </svg>
    </span>
    <span v-if="label" class="je-checkbox-label">{{ label }}</span>
  </label>
</template>

<script setup lang="ts">
/**
 * 自定义复选框组件。
 *
 * <p>支持 v-model 绑定，提供统一的主题样式。</p>
 */

interface Props {
  /** 是否选中。 */
  modelValue: boolean
  /** 标签文本，可选。 */
  label?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
})

const emit = defineEmits<{
  /** 选中状态变化时触发。 */
  (e: 'update:modelValue', value: boolean): void
}>()

/**
 * 处理 change 事件。
 *
 * @param event 输入事件
 */
function onChange(event: Event): void {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.checked)
}
</script>

<style scoped>
.je-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.je-checkbox.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.je-checkbox input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.je-checkbox-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 2px solid var(--editor-border);
  border-radius: 3px;
  background: var(--editor-surface);
  transition: background 0.15s ease, border-color 0.15s ease;
  flex-shrink: 0;
}

.je-checkbox input:checked + .je-checkbox-box {
  background: var(--editor-accent);
  border-color: var(--editor-accent);
}

.je-checkbox input:focus + .je-checkbox-box {
  outline: 2px solid var(--editor-accent);
  outline-offset: 1px;
}

.je-checkbox-check {
  width: 14px;
  height: 14px;
  fill: white;
}

.je-checkbox-label {
  font-size: 13px;
  color: var(--editor-text);
}
</style>
