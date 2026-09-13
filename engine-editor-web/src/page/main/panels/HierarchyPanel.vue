<template>
  <div class="panel hierarchy-panel">
    <JavelinTree
        v-if="scene"
        :data="treeData"
        node-key="id"
        label-key="name"
        children-key="children"
        :selected-key="selectedId"
        @select="onSelect"
        @contextmenu="onContextMenu"
    >
      <template #icon="{ node }">
        <img
            class="hierarchy-node-icon"
            :src="getEntityIcon(node)"
            alt=""
        />
      </template>
    </JavelinTree>
    <div v-else class="empty-tip">暂无场景</div>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import JavelinTree from '../../../components/common/tree/JavelinTree.vue'
import {setSelectedEntityId} from '../composables/useSelection.ts'
import type {TreeNode} from '../../../components/common/types.ts'
const boxIcon = '/icon/box.svg'

/**
 * 场景数据 VO（临时定义，待服务层搭建后迁移）。
 */
interface GameSceneVo {
  id: number
  name: string
  dirty: boolean
  entities: TreeNode[]
}

/**
 * 层级面板。
 *
 * <p>展示当前场景中的实体层级关系，支持节点右键菜单。</p>
 */

const scene = ref<GameSceneVo | null>(null)
const selectedId = ref<number | undefined>(undefined)

const treeData = computed<TreeNode[]>(() => {
  if (scene.value == null) {
    return []
  }
  const sceneNode: TreeNode = {
    id: scene.value.id,
    name: scene.value.dirty ? `${scene.value.name} *` : scene.value.name,
    children: scene.value.entities
  }
  return [sceneNode]
})

const hasSelectedEntity = computed<boolean>(() => {
  if (selectedId.value == null) {
    return false
  }
  return selectedId.value !== scene.value?.id
})

function loadScene(): void {
  // TODO: 后端接口对接
  console.log('[HierarchyPanel] loadScene - 待对接后端接口')
  // 临时：模拟数据
  scene.value = {
    id: 0,
    name: 'Sample Scene',
    dirty: false,
    entities: [
      {id: 1, name: 'Camera', children: []},
      {id: 2, name: 'Light', children: []},
      {id: 3, name: 'Player', children: [
        {id: 4, name: 'Mesh', children: []},
        {id: 5, name: 'Collider', children: []}
      ]}
    ]
  }
}

function getEntityIcon(_node: TreeNode): string {
  return boxIcon
}

function onSelect(node: TreeNode): void {
  console.log("onSelect", node)
  const id = node.id
  if (id == null) {
    return
  }
  selectedId.value = id as number
  setSelectedEntityId(id as number)
  if (!isSceneRoot(node)) {
    // TODO: 后端接口对接
    console.log('[HierarchyPanel] selectEntity - 待对接后端接口, id:', id)
  }
}

/**
 * 处理节点右键事件。
 *
 * @param node 当前节点数据
 * @param event 鼠标事件
 */
function onContextMenu(node: TreeNode, event: MouseEvent): void {
  onSelect(node)
  // TODO: 后端接口对接
  console.log('[HierarchyPanel] showMenu - 待对接后端接口, type: hierarchy, x:', event.clientX, 'y:', event.clientY)
}

function isSceneRoot(node: TreeNode | null): boolean {
  return node != null && node.id === scene.value?.id
}

onMounted(() => {
  loadScene()
})

onUnmounted(() => {
})

/**
 * 处理场景变更消息。
 *
 * @param data 消息数据
 */
function handleSceneChanged(data: { type: string; entityId: number | null }): void {
  console.log("handleSceneChanged", data)
  loadScene()
}

/**
 * 处理场景保存消息。
 *
 * <p>保存成功后把场景脏标记置为 false，去掉标题上的 {@code *}。</p>
 */
function handleSceneSaved(): void {
  if (scene.value != null) {
    scene.value.dirty = false
  }
}

/**
 * 处理场景脏状态变更消息。
 *
 * @param data 脏状态数据
 */
function handleSceneDirty(data: { dirty: boolean }): void {
  if (scene.value != null) {
    scene.value.dirty = data.dirty
  }
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 8px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--editor-text-dim);
  font-size: 11px;
  text-transform: uppercase;
  margin-bottom: 8px;
}

.hierarchy-node-icon {
  width: 14px;
  height: 14px;
}

.empty-tip {
  color: var(--editor-text-dim);
  font-size: 12px;
  text-align: center;
  margin-top: 16px;
}
</style>
