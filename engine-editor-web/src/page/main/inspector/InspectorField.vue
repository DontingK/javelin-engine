<template>
  <div class="inspector-field">
    <span class="field-name">{{ field.name }}</span>
    <div class="field-control">
      <InsBoolean
        v-if="field.valueType === 'BOOLEAN'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsEnum
        v-else-if="field.valueType === 'ENUM'"
        :model-value="field.value"
        :options="field.options || []"
        @update:model-value="onUpdate"
      />
      <InsInt
        v-else-if="field.valueType === 'INT'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsFloat
        v-else-if="field.valueType === 'FLOAT'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsString
        v-else-if="field.valueType === 'STRING'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsVector3
        v-else-if="field.valueType === 'VECTOR3'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsRotation
        v-else-if="field.valueType === 'ROTATION'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsRect
        v-else-if="field.valueType === 'RECT'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsLayerMask
        v-else-if="field.valueType === 'LAYER_MASK'"
        :model-value="field.value"
        :layer-names="layerNames"
        @update:model-value="onUpdate"
      />
      <InsColor
        v-else-if="field.valueType === 'COLOR'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsClearFlags
        v-else-if="field.valueType === 'CLEAR_FLAGS'"
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
      <InsString
        v-else
        :model-value="field.value"
        @update:model-value="onUpdate"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
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
import InsClearFlags from './input/InsClearFlags.vue'

/**
 * Inspector 字段 VO（临时定义，待服务层搭建后迁移）。
 */
interface InspectorFieldVo {
  objectId: number
  fieldName: string
  name: string
  value: string
  valueType: string
  options?: string[]
}

/**
 * Inspector 单个字段渲染组件。
 *
 * <p>根据 {@link InspectorFieldVo.valueType} 选择对应的 Ins 输入控件。</p>
 * <p>所有 Ins 组件的 v-model 类型为 string，与后端 InspectorFieldType 的序列化格式对应：</p>
 * <ul>
 *   <li>INT: 整数字符串</li>
 *   <li>FLOAT: 浮点数字符串</li>
 *   <li>BOOLEAN: "true" 或 "false"</li>
 *   <li>STRING: 原样字符串</li>
 *   <li>ENUM: 枚举常量名称</li>
 *   <li>VECTOR3: "x,y,z"</li>
 *   <li>ROTATION: "x,y,z"（欧拉角度，度）</li>
 *   <li>COLOR: "r,g,b,a"（0-1 浮点数）</li>
 *   <li>RECT: "x,y,width,height"</li>
 *   <li>LAYER_MASK: 整数字符串</li>
 *   <li>CLEAR_FLAGS: "color,depth,stencil,clearColor,clearDepth,clearStencil"</li>
 * </ul>
 */

interface Props {
  /** 字段数据。 */
  field: InspectorFieldVo
  /** 层名称列表，用于 LAYER_MASK 类型。 */
  layerNames?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  layerNames: () => ['Default', '', '', '', '', 'UI', '', '', 'Enemy', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', ''],
})

const emit = defineEmits<{
  /** 字段 value 字符串变化时触发。 */
  (e: 'update:value', value: string): void
}>()

/**
 * 统一处理所有 Ins 组件的更新。
 * Ins 组件的 v-model 都是 string 类型，直接透传。
 */
function onUpdate(value: string): void {
  emit('update:value', value)
}
</script>

<style scoped>
.inspector-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 6px;
  background: var(--editor-surface-light);
  border-radius: 3px;
}

.field-name {
  color: var(--editor-text-dim);
  font-size: 12px;
  flex-shrink: 0;
}

.field-control {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: flex-end;
}
</style>
