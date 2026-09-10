<template>
  <div class="ins-vector3">
    <div class="ins-vector-axis">
      <span class="axis-label">{{ labels[0] }}</span>
      <JeNumberInput
        :model-value="parsedValue.x"
        :integer="integer"
        @update:model-value="updateAxis('x', $event)"
      />
    </div>
    <div class="ins-vector-axis">
      <span class="axis-label">{{ labels[1] }}</span>
      <JeNumberInput
        :model-value="parsedValue.y"
        :integer="integer"
        @update:model-value="updateAxis('y', $event)"
      />
    </div>
    <div class="ins-vector-axis">
      <span class="axis-label">{{ labels[2] }}</span>
      <JeNumberInput
        :model-value="parsedValue.z"
        :integer="integer"
        @update:model-value="updateAxis('z', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeNumberInput from '../../../../components/input/JeNumberInput.vue'

/**
 * 三维向量输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，格式 "x,y,z"，与后端 InspectorFieldType.VECTOR3 对应。</p>
 * <p>内部解析为三个独立的数值输入框。</p>
 */

interface Vector3Value {
  x: number
  y: number
  z: number
}

interface Props {
  /** 当前向量值字符串，格式 "x,y,z"。 */
  modelValue: string
  /** 三个轴的标签，默认为 X/Y/Z。 */
  labels?: [string, string, string]
  /** 是否使用整数，默认为 false。 */
  integer?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  labels: () => ['X', 'Y', 'Z'],
  integer: false,
})

const emit = defineEmits<{
  /** 向量值字符串变化时触发，格式 "x,y,z"。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 解析字符串为 Vector3Value。
 */
const parsedValue = computed<Vector3Value>(() => {
  const parts = props.modelValue.split(',').map(Number.parseFloat)
  return {
    x: Number.isFinite(parts[0]) ? parts[0] : 0,
    y: Number.isFinite(parts[1]) ? parts[1] : 0,
    z: Number.isFinite(parts[2]) ? parts[2] : 0,
  }
})

/**
 * 更新指定轴的值。
 *
 * @param axis 轴名
 * @param value 新值
 */
function updateAxis(axis: keyof Vector3Value, value: number): void {
  const current = parsedValue.value
  const updated = { ...current, [axis]: value }
  emit('update:modelValue', `${updated.x},${updated.y},${updated.z}`)
}
</script>

<style scoped>
.ins-vector3 {
  display: flex;
  gap: 8px;
  width: 100%;
}

.ins-vector-axis {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  gap: 4px;
}

.axis-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--editor-text-dim);
  flex-shrink: 0;
}
</style>
