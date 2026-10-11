<template>
  <div class="ins-rotation">
    <div class="ins-rotation-euler">
      <div class="ins-vector-axis">
        <span class="axis-label">X</span>
        <JeNumberInput
          :model-value="parsedValue.x"
          @update:model-value="updateAxis('x', $event)"
        />
      </div>
      <div class="ins-vector-axis">
        <span class="axis-label">Y</span>
        <JeNumberInput
          :model-value="parsedValue.y"
          @update:model-value="updateAxis('y', $event)"
        />
      </div>
      <div class="ins-vector-axis">
        <span class="axis-label">Z</span>
        <JeNumberInput
          :model-value="parsedValue.z"
          @update:model-value="updateAxis('z', $event)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import JeNumberInput from '../../../../components/input/JeNumberInput.vue'

/**
 * 旋转输入组件（Inspector 专用）。
 *
 * <p>v-model 类型为 string，格式 "x,y,z"（欧拉角度，单位为度），
 * 与后端 InspectorFieldType.ROTATION 对应。后端负责四元数↔欧拉角转换。</p>
 */

interface EulerValue {
  x: number
  y: number
  z: number
}

interface Props {
  /** 当前旋转值字符串，格式 "x,y,z"（欧拉角度，单位为度）。 */
  modelValue: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** 旋转值字符串变化时触发，格式 "x,y,z"（度）。 */
  (e: 'update:modelValue', value: string): void
}>()

/**
 * 解析字符串为欧拉角度值。
 */
const parsedValue = computed<EulerValue>(() => {
  const parts = props.modelValue.split(',').map(Number.parseFloat)
  return {
    x: Number.isFinite(parts[0]) ? parts[0] : 0,
    y: Number.isFinite(parts[1]) ? parts[1] : 0,
    z: Number.isFinite(parts[2]) ? parts[2] : 0,
  }
})

/**
 * 更新指定轴的角度值。
 *
 * @param axis 轴名
 * @param value 新值（度）
 */
function updateAxis(axis: keyof EulerValue, value: number): void {
  const current = parsedValue.value
  const updated = { ...current, [axis]: value }
  emit('update:modelValue', `${updated.x},${updated.y},${updated.z}`)
}
</script>

<style scoped>
.ins-rotation {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
}

.ins-rotation-euler {
  display: flex;
  gap: 2px;
  width: 100%;
}

.ins-vector-axis {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  gap: 2px;
}

.axis-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--editor-text-dim);
  flex-shrink: 0;
}
</style>
