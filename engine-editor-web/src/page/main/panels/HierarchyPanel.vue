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
    <div v-else class="empty-tip">场景加载中…</div>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import JavelinTree from '../../../components/common/tree/JavelinTree.vue'
import {setSelectedEntityId} from '../composables/useSelection.ts'
import {loadSceneTree, selectNode} from '../../../service/sceneService.ts'
import {openMenu} from '../../../service/menuService.ts'
import {subscribeEvent, unsubscribeEvent} from '../../../service/eventService.ts'
import type {TreeNode} from '../../../components/common/types.ts'
const boxIcon = '/icon/box.svg'

/**
 * 场景数据 VO（Java 侧 SceneTreeVo 拉取结果适配）。
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
 * <p>展示用户场景（EditorScene.gameScene）的节点层级：树快照经 /scene/tree 拉取
 * （ready=false 时节流重试兜底启动竞态）；选中经 /scene/select 上报 Java 侧
 * EditorSelection 单源；结构变更经 scene.changed 事件驱动重新拉取（命令为
 * fire-and-forget，状态以拉取为准）；名称变更经 scene.nodeRenamed 定点更新树行
 * （SceneWatchAppState 快照 diff 检出，不重拉整树）并镜像置 dirty 星标；右键经 /menu/open 传 ctx.hierarchy 域 id +
 * 鼠标坐标弹 Swing 菜单（菜单渲染统一 Swing——Web 浮层会被视口 Canvas 盖，
 * 设计档 Editor_Menu_Design §5.2），菜单项点击在 Java 侧直执行命令。</p>
 */

const scene = ref<GameSceneVo | null>(null)
const selectedId = ref<number | undefined>(undefined)

/** 场景未就绪重试（启动竞态：引擎装配可能晚于页面挂载，对齐 MenuBar 节流重试口径） */
const MAX_RETRY = 30
const RETRY_DELAY_MS = 500
let retryCount = 0
let disposed = false

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

/**
 * 拉取场景树快照并同步选中态（selectedNodeId 为 Java 侧 EditorSelection 快照——
 * 创建命令执行后自动选中新节点经此对齐）。
 */
async function loadScene(): Promise<void> {
  if (disposed) {
    return
  }
  try {
    const vo = await loadSceneTree()
    if (!vo.ready) {
      retryOrFail('场景未就绪')
      return
    }
    retryCount = 0
    scene.value = {id: vo.id, name: vo.name, dirty: vo.dirty, entities: vo.entities}
    selectedId.value = vo.selectedNodeId ?? undefined
    setSelectedEntityId(vo.selectedNodeId ?? undefined)
  } catch (e) {
    retryOrFail(`拉取场景树失败: ${e}`)
  }
}

/**
 * 节流重试，达上限放弃并记警告。
 *
 * @param reason 本次失败原因（日志用）
 */
function retryOrFail(reason: string): void {
  if (retryCount++ < MAX_RETRY) {
    setTimeout(loadScene, RETRY_DELAY_MS)
  } else {
    console.warn(`[HierarchyPanel] ${reason}，已达重试上限（${MAX_RETRY} 次）`)
  }
}

function getEntityIcon(_node: TreeNode): string {
  return boxIcon
}

/**
 * 本地选中高亮（同步：面板选中态 + 全局选中 composable）。
 *
 * @param node 当前节点数据
 */
function applySelection(node: TreeNode): void {
  const id = node.id
  if (id == null) {
    return
  }
  selectedId.value = id as number
  setSelectedEntityId(id as number)
}

/**
 * 选中上报（写 Java 侧 EditorSelection 单源，volatile 直写返回即生效；选中场景根 =
 * 清除 Node 选中——场景根非 Node，上报 null）。
 *
 * @param node 当前节点数据
 * @returns 上报 promise（右键路径 await 后再弹菜单——Swing 菜单显示前求值 enabled 读
 *          Java 侧选中，须先落上报防求值读到旧态）
 */
function reportSelection(node: TreeNode): Promise<void> {
  return selectNode(isSceneRoot(node) ? null : node.id as number)
}

/**
 * 节点单击选中：本地高亮 + 上报。
 *
 * @param node 当前节点数据
 */
function onSelect(node: TreeNode): void {
  applySelection(node)
  reportSelection(node).catch(e => console.warn('[HierarchyPanel] 选中上报失败', e))
}

/**
 * 处理节点右键事件：先选中并上报（右键即选中，Unity 语义），再经 /menu/open 传
 * ctx.hierarchy 域 id + 鼠标坐标（web 坐标 = contentPane 坐标，同顶栏口径），
 * Java 侧 EDT 于鼠标位置弹 Swing JPopupMenu（统一 Swing 渲染，heavyweight popup
 * 浮于视口 Canvas 之上）；菜单项点击由 Java 侧 ActionListener 直执行命令。
 *
 * @param node 当前节点数据
 * @param event 鼠标事件
 */
async function onContextMenu(node: TreeNode, event: MouseEvent): Promise<void> {
  applySelection(node)
  try {
    await reportSelection(node)
    await openMenu('ctx.hierarchy', event.clientX, event.clientY)
  } catch (e) {
    console.warn('[HierarchyPanel] 右键菜单链路失败', e)
  }
}

function isSceneRoot(node: TreeNode | null): boolean {
  return node != null && node.id === scene.value?.id
}

/**
 * 场景结构变更事件处理（scene.changed）：重新拉取树（含 dirty/选中态对齐）。
 */
const onSceneChanged = (_data: unknown): void => {
  loadScene()
}

/**
 * 场景脏标记事件处理（scene.dirty）：本地镜像置星标（Java 侧 markDirty 由净
 * 变脏翻转沿发布——Inspector 任何属性回写/变换检出即时亮星），<b>不重拉树</b>
 * （树内容未变，重拉归 scene.changed）；树未加载则静默丢弃（届时 loadScene
 * 以 VO dirty 为准）。熄灭归保存路径 scene.changed(false) 重拉。
 */
const onSceneDirty = (_data: unknown): void => {
  const sceneVo = scene.value
  if (sceneVo != null) {
    sceneVo.dirty = true
  }
}

/**
 * 节点更名事件处理（scene.nodeRenamed）：定点更新对应树行名称（Inspector 改名、
 * 将来 rename 命令同走此事件）；树未加载或节点已删则静默无操作。
 */
const onNodeRenamed = (data: { nodeId: number, name: string }): void => {
  const sceneVo = scene.value
  if (sceneVo == null) {
    return
  }
  if (sceneVo.entities != null) {
    updateNodeName(sceneVo.entities, data.nodeId, data.name)
  }
  // 镜像 Java 侧 markDirty（diff 检出改名必已置位）：星标即时出现；
  // 保存后的 scene.changed(false) 重拉以 VO dirty 为准熄灭
  sceneVo.dirty = true
}

/** 沿树 DFS 定点改名（命中即改） */
function updateNodeName(nodes: TreeNode[], nodeId: number, name: string): void {
  for (const node of nodes) {
    if (node.id === nodeId) {
      node.name = name
      return
    }
    if (node.children != null) {
      updateNodeName(node.children, nodeId, name)
    }
  }
}

onMounted(async () => {
  await subscribeEvent('scene.changed', onSceneChanged)
  await subscribeEvent('scene.dirty', onSceneDirty)
  await subscribeEvent('scene.nodeRenamed', onNodeRenamed)
  loadScene()
})

onUnmounted(() => {
  disposed = true
  unsubscribeEvent('scene.changed', onSceneChanged)
      .catch(e => console.warn('[HierarchyPanel] 事件退订失败', e))
  unsubscribeEvent('scene.dirty', onSceneDirty)
      .catch(e => console.warn('[HierarchyPanel] 事件退订失败', e))
  unsubscribeEvent('scene.nodeRenamed', onNodeRenamed)
      .catch(e => console.warn('[HierarchyPanel] 事件退订失败', e))
})
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 8px;
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
