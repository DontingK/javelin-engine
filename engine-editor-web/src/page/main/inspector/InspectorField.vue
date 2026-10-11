<template>
  <div class="inspector-field">
    <span class="field-name">{{ field.name }}</span>
    <div class="field-control">
      <InsBoolean
        v-if="field.valueType === 'BOOLEAN'"
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
      <InsEnum
        v-else-if="field.valueType === 'ENUM'"
        :model-value="fieldValue"
        :options="field.options ?? []"
        @update:model-value="onUpdate"
      />
      <InsInt
        v-else-if="field.valueType === 'INT'"
        :model-value="fieldValue"
        :min="field.range?.min"
        :max="field.range?.max"
        @update:model-value="onUpdate"
      />
      <InsFloat
        v-else-if="field.valueType === 'FLOAT'"
        :model-value="fieldValue"
        :min="field.range?.min"
        :max="field.range?.max"
        :step="field.range?.step"
        @update:model-value="onUpdate"
      />
      <InsString
        v-else-if="field.valueType === 'STRING'"
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
      <InsVector3
        v-else-if="field.valueType === 'VEC3_F'"
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
      <InsRotation
        v-else-if="field.valueType === 'ROTATION'"
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
      <InsRect
        v-else-if="field.valueType === 'RECT'"
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
      <InsLayerMask
        v-else-if="field.valueType === 'LAYER_MASK'"
        :model-value="fieldValue"
        :layer-names="layerNames"
        @update:model-value="onUpdate"
      />
      <InsColor
        v-else-if="field.valueType === 'COLOR'"
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
      <InsString
        v-else
        :model-value="fieldValue"
        @update:model-value="onUpdate"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import InsBoolean from './input/InsBoolean.vue'
import InsEnum from './input/InsEnum.vue'
import InsInt from './input/InsInt.vue'
import InsFloat from './input/InsFloat.vue'
import InsString from './input/InsString.vue'
import InsVector3 from './input/InsVector3.vue'
import InsRotation from './input/InsRotation.vue'
import InsRect from './input/InsRect.vue'
import InsLayerMask from './input/InsLayerMask.vue'
import InsColor from './input/InsColor.vue'
import type { InspectorFieldVo } from '../../../service/inspectorService.ts'

/**
 * Inspector 单字段行渲染：按 valueType 分发到 Ins 输入控件。
 *
 * <p>valueType 串 = Java 侧 InspectorValueType 常量名（严格大小写）。
 * 所有 Ins 控件 v-model 均为 string，与 valueType 的序列化格式对应：
 * INT/FLOAT 数字串、BOOLEAN "true"/"false"、STRING 原样、ENUM 常量名、
 * VEC3_F "x,y,z"、ROTATION "x,y,z"（欧拉角度数）、COLOR "r,g,b,a"（0-1）、
 * RECT "x,y,width,height"、LAYER_MASK 位掩码 int 串。</p>
 * <p>range（@InsIntRange/@InsFloatRange 投影）非空时数值控件按滑条元数据约束，
 * layerNames 由面板随 VO 下传（LAYER_MASK 勾选项）。</p>
 */
interface Props {
  /** 字段数据。 */
  field: InspectorFieldVo
  /** Layer 名列表，用于 LAYER_MASK 类型。 */
  layerNames?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  layerNames: () => [],
})

const emit = defineEmits<{
  /** 字段 value 字符串变化时触发。 */
  (e: 'update:value', value: string): void
}>()

/** 空值兜底空串（Ins 控件 modelValue 契约均为非空 string）。 */
const fieldValue = computed<string>(() => props.field.value ?? '')

/**
 * 统一处理所有 Ins 组件的更新（v-model 均 string，直接透传）。
 */
function onUpdate(value: string): void {
  emit('update:value', value)
}
</script>

<style scoped>
/* 两列布局（label 列对齐）：列轨由 .property-group 网格定（subgrid 对齐组内
   最长 label），无 subgrid 支持时回退本行 auto（按本行 label 自宽，等价旧行为）。
   label 与控件间隔 4px 为用户拍板（2026-10-11），突破间距 ≤2 通用规范。 */
.inspector-field {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: auto 1fr;
  grid-template-columns: subgrid;
  column-gap: 4px;
  align-items: start;   /* label 顶部对齐（多行控件如视口矩形 X/Y+W/H 两行），不垂直居中 */
  padding: 2px;
  background: var(--editor-surface-light);
  border-radius: 3px;
}

.field-name {
  color: var(--editor-text-dim);
  font-size: 12px;
  display: block;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-control {
  min-width: 0;
  display: flex;
  justify-content: flex-end;
}
</style>
