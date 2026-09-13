<template>
  <div class="ins-clear-flags">
    <div class="flags-row">
      <label class="flag-item">
        <JeCheckBox
          :model-value="flags.color"
          @update:model-value="toggleFlag('color', $event)"
        />
        <span class="flag-name">Color</span>
      </label>
      <label class="flag-item">
        <JeCheckBox
          :model-value="flags.depth"
          @update:model-value="toggleFlag('depth', $event)"
        />
        <span class="flag-name">Depth</span>
      </label>
      <label class="flag-item">
        <JeCheckBox
          :model-value="flags.stencil"
          @update:model-value="toggleFlag('stencil', $event)"
        />
        <span class="flag-name">Stencil</span>
      </label>
    </div>
    <div class="values-row">
      <div class="value-item">
        <span class="value-label">Clear Color</span>
        <InsColor
          :model-value="clearColorValue"
          @update:model-value="updateClearColor"
        />
      </div>
      <div class="value-item">
        <span class="value-label">Clear Depth</span>
        <JeNumberInput
          :model-value="flags.clearDepth"
          :min="0"
          :max="1"
          :step="0.01"
          @update:model-value="updateClearDepth"
        />
      </div>
      <div class="value-item">
        <span class="value-label">Clear Stencil</span>
        <JeNumberInput
          :model-value="flags.clearStencil"
          :integer="true"
          :min="0"
          :max="255"
          @update:model-value="updateClearStencil"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeCheckBox from '../../../../components/input/JeCheckBox.vue'
import JeNumberInput from '../../../../components/input/JeNumberInput.vue'
import InsColor from './InsColor.vue'

/**
 * 缓冲清除标志输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，格式为 "color,depth,stencil,clearColor,clearDepth,clearStencil"。</p>
 * <p>上半部分为 Color/Depth/Stencil 三个 Checkbox 开关，下半部分为 Clear Color（颜色选择器）、
 * Clear Depth（浮点数 0-1）、Clear Stencil（整数 0-255）的输入控件。</p>
 */

interface Props {
  /** 当前清除标志值字符串。 */
  modelValue: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** 清除标志值变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

/** 默认清除标志值。 */
const DEFAULT_VALUE = 'true,true,false,1.0,1.0,1.0,0'

/**
 * 解析清除标志字符串。
 */
const flags = computed(() => {
  const parts = (props.modelValue || DEFAULT_VALUE).split(',')
  return {
    color: parts[0] === 'true',
    depth: parts[1] === 'true',
    stencil: parts[2] === 'true',
    clearColor: parts[3] ?? '1.0,1.0,1.0,1.0',
    clearDepth: parseFloat(parts[4] ?? '1.0'),
    clearStencil: parseInt(parts[5] ?? '0', 10),
  }
})

/**
 * clearColor 字段用 InsColor 的格式（r,g,b,a）。
 */
const clearColorValue = computed(() => flags.value.clearColor)

/**
 * 切换指定标志的启用状态。
 *
 * @param flag 标志名称
 * @param enabled 是否启用
 */
function toggleFlag(flag: 'color' | 'depth' | 'stencil', enabled: boolean): void {
  const current = flags.value
  const newValue = [
    flag === 'color' ? enabled : current.color,
    flag === 'depth' ? enabled : current.depth,
    flag === 'stencil' ? enabled : current.stencil,
    current.clearColor,
    current.clearDepth,
    current.clearStencil,
  ].join(',')
  emit('update:modelValue', newValue)
}

/**
 * 更新清除颜色值。
 *
 * @param value 新的颜色值字符串（r,g,b,a）
 */
function updateClearColor(value: string): void {
  const current = flags.value
  const newValue = [
    current.color,
    current.depth,
    current.stencil,
    value,
    current.clearDepth,
    current.clearStencil,
  ].join(',')
  emit('update:modelValue', newValue)
}

/**
 * 更新清除深度值。
 *
 * @param value 新的深度值
 */
function updateClearDepth(value: number): void {
  const current = flags.value
  const newValue = [
    current.color,
    current.depth,
    current.stencil,
    current.clearColor,
    value,
    current.clearStencil,
  ].join(',')
  emit('update:modelValue', newValue)
}

/**
 * 更新清除模板值。
 *
 * @param value 新的模板值
 */
function updateClearStencil(value: number): void {
  const current = flags.value
  const newValue = [
    current.color,
    current.depth,
    current.stencil,
    current.clearColor,
    current.clearDepth,
    value,
  ].join(',')
  emit('update:modelValue', newValue)
}
</script>

<style scoped>
.ins-clear-flags {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.flags-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.flag-item {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
}

.flag-name {
  font-size: 12px;
  color: var(--editor-text);
}

.values-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.value-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.value-label {
  font-size: 11px;
  color: var(--editor-text-dim);
  width: 80px;
  flex-shrink: 0;
  text-align: right;
}
</style>
