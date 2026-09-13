<template>
  <div class="ins-color">
    <JeColor
      :model-value="colorValue"
      format="rgba"
      @update:model-value="onUpdate"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeColor from '../../../../components/input/JeColor.vue'

/**
 * 颜色输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，格式 "r,g,b,a"（0-1 浮点数），
 * 与后端 InspectorFieldType.COLOR 对应。内部使用 {@link JeColor} 组件，
 * 以 rgba 格式与 JeColor 交互。</p>
 */

interface Props {
  /** 当前颜色值字符串，格式 "r,g,b,a"（0-1 浮点数）。 */
  modelValue: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** 颜色值变化时触发，格式 "r,g,b,a"。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 将后端 0-1 浮点格式转换为 JeColor 的 rgba 格式。
 */
function toRgba(value: string): string {
  const parts = value.split(',').map(Number.parseFloat)
  const r = Number.isFinite(parts[0]) ? Math.round(parts[0] * 255) : 0
  const g = Number.isFinite(parts[1]) ? Math.round(parts[1] * 255) : 0
  const b = Number.isFinite(parts[2]) ? Math.round(parts[2] * 255) : 0
  const a = Number.isFinite(parts[3]) ? parts[3] : 1
  return `rgba(${r},${g},${b},${a.toFixed(3)})`
}

/**
 * 将 JeColor 的 rgba 格式转换回后端 0-1 浮点格式。
 */
function fromRgba(rgba: string): string {
  const match = rgba.match(/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/)
  if (!match) {
    return '0,0,0,1'
  }
  const r = (parseInt(match[1], 10) / 255).toFixed(3)
  const g = (parseInt(match[2], 10) / 255).toFixed(3)
  const b = (parseInt(match[3], 10) / 255).toFixed(3)
  const a = parseFloat(match[4]).toFixed(3)
  return `${r},${g},${b},${a}`
}

/**
 * 用于 JeColor 的显示值。
 */
const colorValue = computed(() => toRgba(props.modelValue))

/**
 * 处理 JeColor 更新。
 */
function onUpdate(value: string): void {
  emit('update:modelValue', fromRgba(value))
}
</script>

<style scoped>
.ins-color {
  width: 100%;
}
</style>
