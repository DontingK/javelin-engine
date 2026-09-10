<template>
  <JeNumberInput
    :model-value="displayValue"
    :integer="true"
    :min="min"
    :max="max"
    :placeholder="placeholder"
    :disabled="disabled"
    class="ins-number"
    @update:model-value="onUpdate"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeNumberInput from '../../../../components/input/JeNumberInput.vue'

/**
 * 整数输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，与后端 InspectorFieldType.INT 对应。</p>
 * <p>内部解析为整数，使用 JeNumberInput 组件。</p>
 */

interface Props {
  /** 当前整数值字符串。 */
  modelValue: string
  /** 最小值。 */
  min?: number
  /** 最大值。 */
  max?: number
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
  /** 整数值字符串变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

const displayValue = computed(() => {
  const parsed = parseInt(props.modelValue, 10)
  return Number.isNaN(parsed) ? 0 : parsed
})

/**
 * 处理数值更新。
 *
 * @param value 新数值
 */
function onUpdate(value: number): void {
  emit('update:modelValue', String(value))
}
</script>

<style scoped>
.ins-number {
  width: 100%;
}
</style>
