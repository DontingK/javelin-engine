<template>
  <ul class="javelin-tree">
    <JavelinTreeNode
      v-for="node in data"
      :key="getKey(node)"
      :node="node"
      :node-key="nodeKey"
      :label-key="labelKey"
      :children-key="childrenKey"
      :icon-key="iconKey"
      :selected-key="selectedKey"
      :default-expanded="defaultExpanded"
      @select="onSelect"
      @contextmenu="onContextMenu"
    >
      <template v-if="$slots.icon" #icon="slotProps">
        <slot name="icon" v-bind="slotProps" />
      </template>
    </JavelinTreeNode>
  </ul>
</template>

<script setup lang="ts">
import JavelinTreeNode from './JavelinTreeNode.vue'
import type { TreeNode } from '../types.ts'

/**
 * 通用树组件。
 *
 * <p>支持通过属性指定节点标识、显示文本、子节点字段名，可复用于层级面板、项目面板等场景。</p>
 */

interface Props {
  /** 树节点数据数组 */
  data: TreeNode[]
  /** 节点唯一标识字段名 */
  nodeKey?: string
  /** 节点显示文本字段名 */
  labelKey?: string
  /** 子节点字段名 */
  childrenKey?: string
  /** 图标地址字段名 */
  iconKey?: string
  /** 当前选中的节点标识 */
  selectedKey?: string | number
  /** 节点默认是否展开 */
  defaultExpanded?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  nodeKey: 'id',
  labelKey: 'name',
  childrenKey: 'children',
  defaultExpanded: true
})

const emit = defineEmits<{
  /** 选中节点时触发，参数为当前节点数据 */
  (e: 'select', node: TreeNode): void
  /** 节点右键时触发，参数为当前节点数据和鼠标事件 */
  (e: 'contextmenu', node: TreeNode, event: MouseEvent): void
}>()

function getKey(node: TreeNode): string | number {
  const value = (node as Record<string, unknown>)[props.nodeKey]
  return value == null ? '' : (value as string | number)
}

function onSelect(node: TreeNode): void {
  emit('select', node)
}

/**
 * 透传节点右键事件。
 *
 * @param node 当前节点数据
 * @param event 鼠标事件
 */
function onContextMenu(node: TreeNode, event: MouseEvent): void {
  emit('contextmenu', node, event)
}
</script>

<style scoped>
.javelin-tree {
  list-style: none;
  padding: 0;
  margin: 0;
  display: inline-block;
  min-width: 100%;
}
</style>
