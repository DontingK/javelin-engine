<template>
  <div class="panel inspector-panel">
    <div v-if="vo" class="entity-header">
      <img class="entity-icon" :src="boxIcon" alt="实体图标" />
      <div class="entity-fields">
        <div class="entity-name-row">
          <JeCheckBox
            v-model="vo.active"
            @update:model-value="onActiveChange"
          />
          <JeInput
            v-model="vo.nodeName"
            class="entity-name-input"
            placeholder="实体名称"
            @blur="onNameChange"
            @keydown.enter="onNameChange"
          />
        </div>
        <div class="entity-meta-row">
          <div class="meta-field">
            <span class="meta-label">标签</span>
            <JeSelect
              :model-value="tagValue"
              class="meta-select"
              :options="tagOptions"
              @update:model-value="onTagChange"
            />
          </div>
          <div class="meta-field">
            <span class="meta-label">图层</span>
            <JeSelect
              :model-value="String(vo.layer)"
              class="meta-select"
              :options="layerOptions"
              @update:model-value="onLayerChange"
            />
          </div>
        </div>
      </div>
    </div>
    <div v-else class="empty-tip">未选中实体</div>

    <div v-if="vo" class="inspector-content">
      <CollapsiblePanel
        v-for="component in vo.components ?? []"
        :key="component.objId"
        :title="component.name"
        :default-expanded="true"
      >
        <template v-if="!component.required" #header-left>
          <JeCheckBox
            :model-value="component.enabled"
            @update:model-value="onComponentEnabledChange(component, $event)"
          />
        </template>
        <div class="property-group">
          <InspectorField
            v-for="field in component.fields"
            :key="field.fieldName"
            :field="field"
            :layer-names="vo.layerNames ?? []"
            @update:value="onFieldChange(field, $event)"
          />
        </div>
      </CollapsiblePanel>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import CollapsiblePanel from '../../../components/common/CollapsiblePanel.vue'
import JeCheckBox from '../../../components/input/JeCheckBox.vue'
import JeInput from '../../../components/input/JeInput.vue'
import JeSelect from '../../../components/input/JeSelect.vue'
import type { SelectOption } from '../../../components/input/JeSelect.vue'
import InspectorField from '../inspector/InspectorField.vue'
import {
  loadInspectorInfo,
  setInspectorValue,
} from '../../../service/inspectorService.ts'
import type {
  ComponentInspectorVo,
  InspectorFieldVo,
  NodeInspectorVo,
} from '../../../service/inspectorService.ts'
import { getSelectedEntityId } from '../composables/useSelection.ts'
import { subscribeEvent, unsubscribeEvent } from '../../../service/eventService.ts'
const boxIcon = '/icon/box.svg'

/**
 * 实体属性面板。
 *
 * <p>选中驱动：订阅 scene.select（EditorSelection 单源收口事件）拉取
 * /inspector/info 快照（pull-on-mount 经 useSelection 兜底启动期选中）；
 * 拖拽/回写刷新：订阅 scene.nodeTransformChanged（TransformWatchAppState
 * 帧级 diff，只盯选中节点）防抖重拉——旋转的四元数↔欧拉角换算单点在 Java 侧
 * （VO 欧拉角 vs 事件四元数），事件载荷不直接消费。</p>
 *
 * <p>写路径：字段/头部编辑乐观更新 + fire-and-forget /inspector/setValue
 * （Java 侧解析 + setter/Tag 专用通道写入 + markDirty 置脏；提示类异常由 Java 弹原生框，
 * 前端不重复弹错）。ready=false 节流重试（30×500ms）兜底启动竞态，
 * 同 HierarchyPanel 范式。</p>
 */

const vo = ref<NodeInspectorVo | null>(null)

/** 当前选中实体 id（重拉/事件过滤基准） */
const currentEntityId = ref<number | null>(null)

/** 未就绪重试（对齐 HierarchyPanel 节流口径） */
const MAX_RETRY = 30
const RETRY_DELAY_MS = 500
let retryCount = 0
let disposed = false

/** Transform 变更防抖重拉间隔（毫秒）——拖拽期间逐帧事件收敛为一次拉取 */
const TRANSFORM_RELOAD_DELAY_MS = 150
let reloadTimer: number | undefined

/** Layer 下拉选项（VO layerNames 按索引投影，未命名空串槽位不渲染） */
const layerOptions = computed<SelectOption[]>(() => {
  const options: SelectOption[] = []
  const names = vo.value?.layerNames ?? []
  names.forEach((name, index) => {
    if (name) {
      options.push({value: String(index), label: name})
    }
  })
  return options
})

/** Tag 下拉选项（VO tagNames ∪ 节点自持 tag——自定义 tag 兜底入选项保显示） */
const tagOptions = computed<SelectOption[]>(() => {
  const names = new Set(vo.value?.tagNames ?? [])
  for (const tag of vo.value?.tags ?? []) {
    names.add(tag)
  }
  return [...names].map(name => ({value: name, label: name}))
})

/** Tag 当前值（节点首个 tag；空 = Unity 内建默认 "Untagged"） */
const tagValue = computed<string>(() => vo.value?.tags?.[0] ?? 'Untagged')

/**
 * 拉取节点 Inspector 快照（ready=false /异常走节流重试）。
 *
 * @param entityId 实体 id
 */
async function loadEntityInspector(entityId: number): Promise<void> {
  if (disposed) {
    return
  }
  currentEntityId.value = entityId
  try {
    const data = await loadInspectorInfo(entityId)
    if (disposed) {
      return
    }
    if (!data.ready) {
      retryOrFail('Inspector 数据未就绪')
      return
    }
    retryCount = 0
    vo.value = data
  } catch (e) {
    retryOrFail(`拉取 Inspector 数据失败: ${e}`)
  }
}

/**
 * 节流重试，达上限放弃并记警告。
 *
 * @param reason 本次失败原因（日志用）
 */
function retryOrFail(reason: string): void {
  const entityId = currentEntityId.value
  if (retryCount++ < MAX_RETRY) {
    setTimeout(() => {
      if (!disposed && entityId != null) {
        loadEntityInspector(entityId)
      }
    }, RETRY_DELAY_MS)
  } else {
    console.warn(`[EntityInspectorPanel] ${reason}，已达重试上限（${MAX_RETRY} 次）`)
  }
}

/**
 * 回写字段值（fire-and-forget：乐观更新由调用方完成，失败仅日志）。
 *
 * @param objId 字段宿主对象 id
 * @param fieldName 字段名
 * @param valueType 值类型（InspectorValueType 常量名）
 * @param value 新值字符串
 */
function sendValue(objId: number, fieldName: string, valueType: string, value: string): void {
  setInspectorValue({objId, fieldName, valueType, value})
      .catch(e => console.warn('[EntityInspectorPanel] 回写失败', fieldName, e))
}

/**
 * 处理字段值变更：乐观更新 + 回写。
 *
 * @param field 变更的字段
 * @param newValue 新值字符串
 */
function onFieldChange(field: InspectorFieldVo, newValue: string): void {
  field.value = newValue
  sendValue(field.objId, field.fieldName, field.valueType, newValue)
}

/**
 * 实体名称变更（v-model 已写本地，此处回写；blur/enter 提交）。
 */
function onNameChange(): void {
  if (vo.value == null) {
    return
  }
  sendValue(vo.value.objId, 'name', 'STRING', vo.value.nodeName)
}

/**
 * 实体激活状态变更（v-model 已写本地，此处回写）。
 *
 * @param value 新激活态
 */
function onActiveChange(value: boolean): void {
  if (vo.value == null) {
    return
  }
  sendValue(vo.value.objId, 'active', 'BOOLEAN', String(value))
}

/**
 * 实体 Layer 变更（下拉单选；本地更新 + 回写，value=层号索引串）。
 *
 * @param value 新 Layer 索引字符串
 */
function onLayerChange(value: string): void {
  if (vo.value == null) {
    return
  }
  vo.value.layer = Number(value)
  sendValue(vo.value.objId, 'layer', 'INT', value)
}

/**
 * 实体 Tag 变更（下拉单选；本地更新 + 回写）。"Untagged"=清空 tag
 * （Java 侧替换式语义，空列表显示侧即 Untagged）。
 *
 * @param value 新 tag 名
 */
function onTagChange(value: string): void {
  if (vo.value == null) {
    return
  }
  vo.value.tags = value === 'Untagged' ? [] : [value]
  sendValue(vo.value.objId, 'tags', 'TAG', value)
}

/**
 * 组件启用状态变更（分组头勾选；本地更新 + 回写）。
 *
 * @param component 目标组件
 * @param value 新启用态
 */
function onComponentEnabledChange(component: ComponentInspectorVo, value: boolean): void {
  component.enabled = value
  sendValue(component.objId, 'enabled', 'BOOLEAN', String(value))
}

/**
 * 选中变更（scene.select）：换节点拉取、清除清面板（同节点不重拉防闪烁）。
 *
 * @param data 事件载荷 {nodeId: number | null}
 */
const onSceneSelect = (data: { nodeId: number | null }): void => {
  if (data.nodeId == null) {
    currentEntityId.value = null
    vo.value = null
    retryCount = 0
    return
  }
  if (data.nodeId !== currentEntityId.value) {
    retryCount = 0
    loadEntityInspector(data.nodeId)
  }
}

/**
 * Transform 变更（scene.nodeTransformChanged）：选中节点的 TRS 实变（拖拽/回写），
 * 防抖重拉对齐数值。
 *
 * @param data 事件载荷 {nodeId: number, position/rotation/scale: number[]}（载荷不直接消费）
 */
const onNodeTransformChanged = (data: { nodeId: number }): void => {
  if (data.nodeId !== currentEntityId.value) {
    return
  }
  if (reloadTimer != null) {
    clearTimeout(reloadTimer)
  }
  reloadTimer = window.setTimeout(() => {
    reloadTimer = undefined
    if (!disposed && currentEntityId.value != null) {
      loadEntityInspector(currentEntityId.value)
    }
  }, TRANSFORM_RELOAD_DELAY_MS)
}

onMounted(async () => {
  await subscribeEvent('scene.select', onSceneSelect)
  await subscribeEvent('scene.nodeTransformChanged', onNodeTransformChanged)
  // pull-on-mount：启动期选中事件必丢（订阅晚于发布），经共享选中态兜底
  const initial = getSelectedEntityId()
  if (initial != null) {
    loadEntityInspector(initial)
  }
})

onUnmounted(() => {
  disposed = true
  if (reloadTimer != null) {
    clearTimeout(reloadTimer)
    reloadTimer = undefined
  }
  unsubscribeEvent('scene.select', onSceneSelect)
      .catch(e => console.warn('[EntityInspectorPanel] 事件退订失败', e))
  unsubscribeEvent('scene.nodeTransformChanged', onNodeTransformChanged)
      .catch(e => console.warn('[EntityInspectorPanel] 事件退订失败', e))
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
  gap: 2px;
  padding: 2px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  margin-bottom: 2px;
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
  gap: 2px;
}

.entity-name-row {
  display: flex;
  align-items: center;
  gap: 2px;
}

.entity-name-input {
  flex: 1;
  min-width: 0;
}

.entity-name-input :deep(.je-input) {
  font-weight: 600;
  color: var(--editor-text-heading);
}

.entity-meta-row {
  display: flex;
  align-items: center;
  gap: 2px;
}

.meta-field {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.meta-label {
  font-size: 12px;
  color: var(--editor-text-dim);
  flex-shrink: 0;
}

.meta-select {
  flex: 1;
  min-width: 0;
}

.inspector-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.property-group {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.empty-tip {
  color: var(--editor-text-dim);
  font-size: 12px;
  text-align: center;
  margin-top: 2px;
}
</style>
