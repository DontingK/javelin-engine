<template>
  <JeSelect
    :model-value="selectedValue"
    :options="layerOptions"
    :multiple="true"
    placeholder="Nothing"
    class="ins-layer-mask"
    @update:model-value="onUpdate"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeSelect from '../../../../components/input/JeSelect.vue'
import type { SelectOption } from '../../../../components/input/JeSelect.vue'

/**
 * 层掩码下拉多选组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，表示整数位掩码的字符串形式，与后端 InspectorFieldType.LAYER_MASK 对应。</p>
 * <p>按位展示 0~31 层的启用状态，常用于 Camera.cullingMask 等属性。</p>
 */

interface Props {
  /** 当前层掩码值字符串（整数的字符串表示）。 */
  modelValue: string
  /** 32 个层的名称数组，未注册位置可为空字符串。 */
  layerNames?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  layerNames: () => [],
})

const emit = defineEmits<{
  /** 掩码值字符串变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 解析字符串为整数掩码。
 */
const maskValue = computed(() => {
  const parsed = parseInt(props.modelValue, 10)
  return Number.isNaN(parsed) ? 0 : parsed
})

/**
 * 规范化层名称数组，确保长度为 32。
 */
const normalizedLayerNames = computed(() => {
  const names = props.layerNames.slice(0, 32)
  while (names.length < 32) {
    names.push('')
  }
  return names
})

/**
 * 生成层选项列表。
 */
const layerOptions = computed<SelectOption[]>(() => {
  return normalizedLayerNames.value
    .map((name, index) => ({
      value: String(index),
      label: name || `Layer ${index}`,
    }))
    .filter((_, index) => normalizedLayerNames.value[index] !== '')
})

/**
 * 当前选中的层索引，逗号分隔。
 */
const selectedValue = computed(() => {
  const indices: string[] = []
  for (let i = 0; i < 32; i++) {
    if ((maskValue.value & (1 << i)) !== 0 && normalizedLayerNames.value[i]) {
      indices.push(String(i))
    }
  }
  return indices.join(',')
})

/**
 * 处理更新，将选中的层索引转换为位掩码。
 *
 * @param value 逗号分隔的层索引
 */
function onUpdate(value: string): void {
  const indices = value.split(',').filter(Boolean)
  let mask = 0
  for (const index of indices) {
    const i = parseInt(index, 10)
    if (!Number.isNaN(i)) {
      mask |= 1 << i
    }
  }
  emit('update:modelValue', String(mask))
}
</script>

<style scoped>
.ins-layer-mask {
  width: 100%;
}
</style>
