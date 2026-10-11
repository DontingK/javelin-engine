<template>
  <select
    :value="modelValue"
    :disabled="disabled"
    class="ins-select"
    @change="onChange"
  >
    <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
    <option
      v-for="option in options"
      :key="option.value"
      :value="option.value"
    >
      {{ option.label }}
    </option>
  </select>
</template>

<script setup lang="ts">
/**
 * 枚举选项 VO（临时定义，待服务层搭建后迁移）。
 */
interface InspectorFieldOptionsVo {
  value: string
  label: string
}

/**
 * 枚举选择组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，值为枚举常量名称，与后端 InspectorFieldType.ENUM 对应。</p>
 */

interface Props {
  /** 当前选中的枚举值字符串。 */
  modelValue: string
  /** 枚举选项列表。 */
  options: InspectorFieldOptionsVo[]
  /** 占位选项文本，可选。 */
  placeholder?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
}

withDefaults(defineProps<Props>(), {
  placeholder: '',
  disabled: false,
})

const emit = defineEmits<{
  /** 选中值变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 处理 change 事件。
 */
function onChange(event: Event): void {
  const target = event.target as HTMLSelectElement
  emit('update:modelValue', target.value)
}
</script>

<style scoped>
.ins-select {
  width: 100%;
  padding: 2px;
  font-size: 13px;
  color: var(--editor-text);
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  outline: none;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.ins-select:hover {
  background: var(--editor-surface);
}

.ins-select:focus {
  background: var(--editor-surface);
  border-color: var(--editor-accent);
}

.ins-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--editor-surface-light);
}
</style>
