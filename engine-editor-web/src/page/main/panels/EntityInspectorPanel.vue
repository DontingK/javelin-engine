<template>
  <div class="panel inspector-panel">
    <div v-if="entityInfo" class="entity-header">
      <img class="entity-icon" :src="boxIcon" alt="实体图标" />
      <div class="entity-fields">
        <div class="entity-name-row">
          <JeInput
            v-model="entityInfo.name"
            class="entity-name-input"
            placeholder="实体名称"
            @blur="onNameChange"
            @keydown.enter="onNameChange"
          />
          <JeCheckBox
            v-model="entityInfo.activeSelf"
            @update:model-value="onActiveSelfChange"
          />
        </div>
        <div class="entity-layer-row">
          <span class="meta-label">Layer</span>
          <JeSelect
            v-model="entityLayerName"
            class="meta-select"
            :options="layerOptions"
            @update:model-value="onLayerChange"
          />
        </div>
      </div>
    </div>
    <div v-else class="empty-tip">未选中实体</div>

    <div v-if="entityInfo" class="inspector-content">
      <CollapsiblePanel
        v-for="component in entityInfo.components"
        :key="component.objectId"
        :title="component.name"
        :icon="component.icon || '📦'"
        :default-expanded="true"
      >
        <div class="property-group">
          <InspectorField
            v-for="field in component.fields"
            :key="field.fieldName"
            :field="field"
            @update:value="onFieldChange(field, $event)"
          />
        </div>
      </CollapsiblePanel>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import CollapsiblePanel from '../../../components/common/CollapsiblePanel.vue'
import JeCheckBox from '../../../components/input/JeCheckBox.vue'
import JeInput from '../../../components/input/JeInput.vue'
import JeSelect from '../../../components/input/JeSelect.vue'
import InspectorField from '../inspector/InspectorField.vue'
import type { SelectOption } from '../../../components/input/JeSelect.vue'
import javaScriptBridge from '../../../bridge/javaScriptBridge.ts'
const boxIcon = '/icon/box.svg'

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
 * 组件 VO（临时定义，待服务层搭建后迁移）。
 */
interface InspectorComponentVo {
  objectId: number
  name: string
  icon?: string
  fields: InspectorFieldVo[]
}

/**
 * 实体 Inspector VO（临时定义，待服务层搭建后迁移）。
 */
interface EntityInspectorVo {
  objectId: number
  name: string
  activeSelf: boolean
  layer: number
  components: InspectorComponentVo[]
}

/**
 * 实体属性面板。
 *
 * <p>监听 {@code selectionChanged} 消息，调用 Inspector API 获取实体数据并展示。
 * 使用 InspectorField 组件根据字段类型渲染对应的输入控件。</p>
 */

const entityInfo = ref<EntityInspectorVo | null>(null)

/**
 * 当前选中的实体 ID，用于判断场景变更消息是否需要刷新本面板。
 */
const currentEntityId = ref<number | null>(null)

const layerOptions: SelectOption[] = [
  { value: '0', label: 'Default' },
  { value: '1', label: 'UI' },
  { value: '2', label: 'Enemy' }
]

/**
 * 实体层名称，将数字索引映射为显示文本。
 */
const entityLayerName = computed({
  get: () => String(entityInfo.value?.layer ?? 0),
  set: (val: string) => {
    if (entityInfo.value) {
      entityInfo.value.layer = parseInt(val, 10)
    }
  }
})

/**
 * 加载实体 Inspector 数据。
 *
 * @param entityId 实体 ID
 */
function loadEntityInspector(entityId: number): void {
  currentEntityId.value = entityId
  // TODO: 后端接口对接
  console.log('[EntityInspectorPanel] loadEntityInspector - 待对接后端接口, entityId:', entityId)
  // 临时：模拟数据
  entityInfo.value = {
    objectId: entityId,
    name: 'Player',
    activeSelf: true,
    layer: 0,
    components: [
      {
        objectId: 100,
        name: 'Transform',
        icon: '📐',
        fields: [
          { objectId: 101, fieldName: 'position', name: 'Position', value: '0,0,0', valueType: 'VECTOR3' },
          { objectId: 102, fieldName: 'rotation', name: 'Rotation', value: '0,0,0,1', valueType: 'ROTATION' },
          { objectId: 103, fieldName: 'scale', name: 'Scale', value: '1,1,1', valueType: 'VECTOR3' }
        ]
      },
      {
        objectId: 200,
        name: 'MeshRenderer',
        icon: '🎨',
        fields: [
          { objectId: 201, fieldName: 'castShadows', name: 'Cast Shadows', value: 'true', valueType: 'BOOLEAN' },
          { objectId: 202, fieldName: 'receiveShadows', name: 'Receive Shadows', value: 'true', valueType: 'BOOLEAN' }
        ]
      }
    ]
  }
}

/**
 * 处理字段值变更，发送到后端。
 *
 * @param field 变更的字段
 * @param newValue 新值
 */
function onFieldChange(field: InspectorFieldVo, newValue: string): void {
  field.value = newValue
  // TODO: 后端接口对接
  console.log('[EntityInspectorPanel] onFieldChange - 待对接后端接口, field:', field.fieldName, 'value:', newValue)
}

/**
 * 处理实体名称变更，发送到后端。
 */
function onNameChange(): void {
  if (entityInfo.value == null) {
    return
  }
  // TODO: 后端接口对接
  console.log('[EntityInspectorPanel] onNameChange - 待对接后端接口, name:', entityInfo.value.name)
}

/**
 * 处理实体激活状态变更，发送到后端。
 */
function onActiveSelfChange(): void {
  if (entityInfo.value == null) {
    return
  }
  // TODO: 后端接口对接
  console.log('[EntityInspectorPanel] onActiveSelfChange - 待对接后端接口, activeSelf:', entityInfo.value.activeSelf)
}

/**
 * 处理实体层变更，发送到后端。
 *
 * @param value 新的层索引字符串
 */
function onLayerChange(value: string): void {
  if (entityInfo.value == null) {
    return
  }
  entityInfo.value.layer = parseInt(value, 10)
  // TODO: 后端接口对接
  console.log('[EntityInspectorPanel] onLayerChange - 待对接后端接口, layer:', value)
}

/**
 * 处理选中变更消息。
 *
 * @param data 消息数据
 */
function handleSelectionChanged(data: { objectId: number | null; objectType: string }): void {
  console.log('handleSelectionChanged', data)
  if (data.objectId == null) {
    currentEntityId.value = null
    entityInfo.value = null
    return
  }
  loadEntityInspector(data.objectId)
}

/**
 * 处理场景变更消息。
 *
 * @param data 消息数据
 */
function handleSceneChanged(data: { type: string; entityId: number | null }): void {
  console.log('handleSceneChanged', data)
  if (data.type === 'TRANSFORM_CHANGED'
      && data.entityId != null
      && data.entityId === currentEntityId.value) {
    loadEntityInspector(data.entityId)
  }
}

onMounted(() => {
  javaScriptBridge.addMsgListener('selectionChanged', handleSelectionChanged)
  javaScriptBridge.addMsgListener('sceneChanged', handleSceneChanged)
})

onUnmounted(() => {
  javaScriptBridge.removeMsgListener('selectionChanged', handleSelectionChanged)
  javaScriptBridge.removeMsgListener('sceneChanged', handleSceneChanged)
})
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 1px;
  overflow: hidden;
}

.entity-header {
  display: flex;
  align-items: stretch;
  gap: 10px;
  padding: 8px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  margin-bottom: 8px;
  flex-shrink: 0;
}

.entity-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  object-fit: contain;
}

.entity-fields {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  gap: 6px;
}

.entity-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entity-name-input {
  flex: 1;
  min-width: 0;
}

.entity-name-input :deep(.je-input) {
  font-weight: 600;
  color: var(--editor-text-heading);
}

.entity-layer-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.meta-label {
  font-size: 12px;
  color: var(--editor-text-dim);
  width: 40px;
  flex-shrink: 0;
}

.meta-select {
  flex: 1;
  min-width: 0;
}

.inspector-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.property-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.empty-tip {
  color: var(--editor-text-dim);
  font-size: 12px;
  text-align: center;
  margin-top: 16px;
}
</style>
