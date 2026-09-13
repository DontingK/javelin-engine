<template>
  <div class="ins-rect">
    <div class="ins-rect-row">
      <div class="ins-rect-field">
        <span class="field-label">X</span>
        <JeNumberInput :model-value="parsedValue.x" @update:model-value="updateField('x', $event)" />
      </div>
      <div class="ins-rect-field">
        <span class="field-label">Y</span>
        <JeNumberInput :model-value="parsedValue.y" @update:model-value="updateField('y', $event)" />
      </div>
    </div>
    <div class="ins-rect-row">
      <div class="ins-rect-field">
        <span class="field-label">W</span>
        <JeNumberInput :model-value="parsedValue.width" @update:model-value="updateField('width', $event)" />
      </div>
      <div class="ins-rect-field">
        <span class="field-label">H</span>
        <JeNumberInput :model-value="parsedValue.height" @update:model-value="updateField('height', $event)" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeNumberInput from '../../../../components/input/JeNumberInput.vue'

/**
 * 矩形区域输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，格式 "x,y,width,height"，与后端 InspectorFieldType.RECT 对应。</p>
 * <p>内部解析为 X/Y/Width/Height 四个数字输入框。</p>
 */

interface RectValue {
  x: number
  y: number
  width: number
  height: number
}

interface Props {
  /** 当前矩形值字符串，格式 "x,y,width,height"。 */
  modelValue: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** 矩形值字符串变化时触发，格式 "x,y,width,height"。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 解析字符串为 RectValue。
 */
const parsedValue = computed<RectValue>(() => {
  const parts = props.modelValue.split(',').map(Number.parseFloat)
  return {
    x: Number.isFinite(parts[0]) ? parts[0] : 0,
    y: Number.isFinite(parts[1]) ? parts[1] : 0,
    width: Number.isFinite(parts[2]) ? parts[2] : 0,
    height: Number.isFinite(parts[3]) ? parts[3] : 0,
  }
})

/**
 * 更新指定字段的值。
 *
 * @param field 字段名
 * @param value 新值
 */
function updateField(field: keyof RectValue, value: number): void {
  const updated = { ...parsedValue.value, [field]: value }
  emit('update:modelValue', `${updated.x},${updated.y},${updated.width},${updated.height}`)
}
</script>

<style scoped>
.ins-rect {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.ins-rect-row {
  display: flex;
  gap: 8px;
}

.ins-rect-field {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  gap: 4px;
}

.field-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--editor-text-dim);
  flex-shrink: 0;
}
</style>
