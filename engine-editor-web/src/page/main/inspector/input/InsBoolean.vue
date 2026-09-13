<template>
  <label class="ins-checkbox" :class="{ disabled: disabled }">
    <input
      type="checkbox"
      :checked="isChecked"
      :disabled="disabled"
      @change="onChange"
    />
    <span class="ins-checkbox-box" aria-hidden="true">
      <svg v-if="isChecked" class="ins-checkbox-check" viewBox="0 0 24 24">
        <path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
      </svg>
    </span>
    <span v-if="label" class="ins-checkbox-label">{{ label }}</span>
  </label>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 布尔复选框组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，值为 "true" 或 "false"，与后端 InspectorFieldType.BOOLEAN 对应。</p>
 */

interface Props {
  /** 当前布尔值字符串（"true" 或 "false"）。 */
  modelValue: string
  /** 标签文本，可选。 */
  label?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
})

const emit = defineEmits<{
  /** 布尔值字符串变化时触发（"true" 或 "false"）。 */
  (e: 'update:modelValue', value: string): void
}>()

const isChecked = computed(() => props.modelValue === 'true')

/**
 * 处理 change 事件。
 */
function onChange(event: Event): void {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', String(target.checked))
}
</script>

<style scoped>
.ins-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.ins-checkbox.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ins-checkbox input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.ins-checkbox-box {
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

.ins-checkbox input:checked + .ins-checkbox-box {
  background: var(--editor-accent);
  border-color: var(--editor-accent);
}

.ins-checkbox input:focus + .ins-checkbox-box {
  outline: 2px solid var(--editor-accent);
  outline-offset: 1px;
}

.ins-checkbox-check {
  width: 14px;
  height: 14px;
  fill: white;
}

.ins-checkbox-label {
  font-size: 13px;
  color: var(--editor-text);
}
</style>
