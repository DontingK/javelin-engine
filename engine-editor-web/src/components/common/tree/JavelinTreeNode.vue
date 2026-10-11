<template>
  <li class="javelin-tree-node">
    <div
      class="javelin-tree-node__content"
      :class="{ 'javelin-tree-node__content--selected': isSelected }"
      :style="{ paddingLeft: `${6 + depth * 16}px` }"
      @click="onNodeClick"
      @contextmenu.prevent="onContextMenu"
    >
      <span
        v-if="hasChildren"
        class="javelin-tree-node__toggle"
        :class="{ 'javelin-tree-node__toggle--expanded': isExpanded }"
        @click.stop="toggle"
      >
        ▶
      </span>
      <span v-else class="javelin-tree-node__toggle-placeholder"></span>
      <span
        v-if="hasIconSlot"
        class="javelin-tree-node__icon"
      >
        <slot
          name="icon"
          :node="node"
          :expanded="isExpanded"
          :has-children="hasChildren"
        />
      </span>
      <img
        v-else-if="iconSrc"
        class="javelin-tree-node__icon"
        :src="iconSrc"
        alt=""
      />
      <span class="javelin-tree-node__label">{{ label }}</span>
    </div>
    <ul
      v-if="isExpanded && hasChildren"
      class="javelin-tree-node__children"
    >
      <JavelinTreeNode
        v-for="child in children"
        :key="getKey(child)"
        :node="child"
        :node-key="nodeKey"
        :label-key="labelKey"
        :children-key="childrenKey"
        :icon-key="iconKey"
        :selected-key="selectedKey"
        :default-expanded="defaultExpanded"
        :depth="depth + 1"
        @select="onChildSelect"
        @contextmenu="onChildContextMenu"
      >
        <template v-if="$slots.icon" #icon="slotProps">
          <slot name="icon" v-bind="slotProps" />
        </template>
      </JavelinTreeNode>
    </ul>
  </li>
</template>

<script setup lang="ts">
import { computed, ref, useSlots, watch } from 'vue'
import type { TreeNode } from '../types.ts'

/**
 * 通用树节点组件。
 *
 * <p>递归渲染节点及其子节点，支持展开/折叠、选中状态与图标展示。</p>
 */

interface Props {
  /** 当前节点数据 */
  node: TreeNode
  /** 节点唯一标识字段名 */
  nodeKey: string
  /** 节点显示文本字段名 */
  labelKey: string
  /** 子节点字段名 */
  childrenKey: string
  /** 图标地址字段名 */
  iconKey?: string
  /** 当前选中的节点标识 */
  selectedKey?: string | number
  /** 节点在树中的深度，用于计算内容缩进 */
  depth?: number
  /** 节点默认是否展开 */
  defaultExpanded?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  depth: 0,
  defaultExpanded: true
})

const emit = defineEmits<{
  /** 选中节点时触发 */
  (e: 'select', node: TreeNode): void
  /** 节点右键时触发 */
  (e: 'contextmenu', node: TreeNode, event: MouseEvent): void
}>()

const slots = useSlots()
const isExpanded = ref(props.defaultExpanded)

function getField(node: TreeNode, key: string): unknown {
  return (node as Record<string, unknown>)[key]
}

function getKey(node: TreeNode): string | number {
  const value = getField(node, props.nodeKey)
  return value == null ? '' : (value as string | number)
}

function getLabel(node: TreeNode): string {
  const value = getField(node, props.labelKey)
  return value == null ? '' : String(value)
}

function getChildren(node: TreeNode): TreeNode[] {
  const value = getField(node, props.childrenKey)
  return Array.isArray(value) ? (value as TreeNode[]) : []
}

const label = computed(() => getLabel(props.node))

const children = computed(() => getChildren(props.node))

const hasChildren = computed(() => children.value.length > 0)

const isSelected = computed(() => props.selectedKey === getKey(props.node))

/**
 * 检查选中的节点是否在当前节点的子树中
 */
function containsSelected(node: TreeNode, selectedKey: string | number | undefined): boolean {
  if (selectedKey == null) return false
  const nodeChildren = getChildren(node)
  for (const child of nodeChildren) {
    if (getKey(child) === selectedKey) return true
    if (containsSelected(child, selectedKey)) return true
  }
  return false
}

const shouldExpand = computed(() => containsSelected(props.node, props.selectedKey))

// 当 selectedKey 变化时，自动展开包含选中节点的父节点
watch(() => props.selectedKey, () => {
  if (shouldExpand.value) {
    isExpanded.value = true
  }
})

const hasIconSlot = computed(() => slots.icon != null)

const iconSrc = computed(() => {
  if (props.iconKey == null) {
    return null
  }
  const value = getField(props.node, props.iconKey)
  return value == null ? null : String(value)
})

function toggle(): void {
  isExpanded.value = !isExpanded.value
}

function onNodeClick(): void {
  emit('select', props.node)
}

/**
 * 处理节点右键事件。
 *
 * @param event 鼠标事件
 */
function onContextMenu(event: MouseEvent): void {
  emit('contextmenu', props.node, event)
}

function onChildSelect(node: TreeNode): void {
  emit('select', node)
}

/**
 * 透传子节点右键事件。
 *
 * @param node 子节点数据
 * @param event 鼠标事件
 */
function onChildContextMenu(node: TreeNode, event: MouseEvent): void {
  emit('contextmenu', node, event)
}
</script>

<style scoped>
.javelin-tree-node {
  user-select: none;
}

.javelin-tree-node__content {
  display: flex;
  align-items: center;
  padding: 2px 6px;
  border-radius: 3px;
  cursor: default;
  width: max-content;
  min-width: 100%;
}

.javelin-tree-node__content:hover {
  background: var(--editor-surface-light);
}

/* 选中行 = accent 蓝底白字（Unity 选中高亮口径）；并集选择器压过 :hover 的
   灰底（同悬停选中行不回灰），展开箭头同步换半透明白 */
.javelin-tree-node__content--selected,
.javelin-tree-node__content--selected:hover {
  background: var(--editor-accent);
  color: #fff;
}

.javelin-tree-node__content--selected .javelin-tree-node__toggle {
  color: rgba(255, 255, 255, 0.75);
}

.javelin-tree-node__toggle,
.javelin-tree-node__toggle-placeholder {
  display: inline-flex;
  width: 14px;
  height: 14px;
  margin-right: 4px;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: var(--editor-text-dim);
  flex-shrink: 0;
}

.javelin-tree-node__toggle {
  cursor: pointer;
  transition: transform 0.15s ease;
}

.javelin-tree-node__toggle--expanded {
  transform: rotate(90deg);
}

.javelin-tree-node__icon {
  display: inline-flex;
  width: 14px;
  height: 14px;
  margin-right: 4px;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.javelin-tree-node__icon img,
.javelin-tree-node__icon svg {
  width: 100%;
  height: 100%;
}

.javelin-tree-node__label {
  flex: 0 0 auto;
  overflow: visible;
  white-space: nowrap;
}

.javelin-tree-node__children {
  padding-left: 0;
  list-style: none;
}
</style>
