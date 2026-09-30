<template>
  <div ref="panelEl" class="panel hierarchy-panel">
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
    <DropdownMenu
        v-if="menu.visible"
        :items="menu.items"
        :x="menu.x"
        :y="menu.y"
        :boundary="panelEl"
        @select="onMenuSelect"
        @close="closeMenu"
    />
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, reactive, ref} from 'vue'
import JavelinTree from '../../../components/common/tree/JavelinTree.vue'
import DropdownMenu from '../../../components/common/DropdownMenu.vue'
import {setSelectedEntityId} from '../composables/useSelection.ts'
import {loadSceneTree, selectNode} from '../../../service/sceneService.ts'
import {executeCommand, listMenuItems, type MenuItemVo} from '../../../service/menuService.ts'
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
 * fire-and-forget，状态以拉取为准）；右键菜单拉取 ctx.hierarchy 域、点击转
 * /command/execute，Web 自渲染（面板不进视口矩形，设计档 Editor_Menu_Design §5.2）。</p>
 */

const scene = ref<GameSceneVo | null>(null)
const selectedId = ref<number | undefined>(undefined)
/** 面板根 DOM（右键菜单夹紧边界——菜单浮层须留在面板矩形内，防延伸进视口被 Canvas 盖） */
const panelEl = ref<HTMLElement | null>(null)
const menu = reactive<{ visible: boolean; items: MenuItemVo[]; x: number; y: number }>({
  visible: false,
  items: [],
  x: 0,
  y: 0
})

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
 * 选中上报（写 Java 侧 EditorSelection 单源；选中场景根 = 清除 Node 选中——
 * 场景根非 Node，上报 null）。
 *
 * @param node 当前节点数据
 * @returns 上报 promise（右键路径 await 后再拉菜单——enabled 求值读 Java 侧选中，
 *          须先落上报防拉到旧状态）
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
 * 处理节点右键事件：先选中并上报（右键即选中，Unity 语义），再拉取 ctx.hierarchy
 * 域菜单结构（每次右键重新拉取，enabled/checked 即时求值）并弹出 Web 菜单。
 *
 * @param node 当前节点数据
 * @param event 鼠标事件
 */
async function onContextMenu(node: TreeNode, event: MouseEvent): Promise<void> {
  applySelection(node)
  try {
    await reportSelection(node)
    const items = await listMenuItems('ctx.hierarchy')
    if (items.length === 0) {
      return
    }
    menu.items = items
    menu.x = event.clientX
    menu.y = event.clientY
    menu.visible = true
  } catch (e) {
    console.warn('[HierarchyPanel] 右键菜单链路失败', e)
  }
}

/**
 * 菜单项点击：转 /command/execute（命令动场景走 Java 侧帧队列，树刷新经
 * scene.changed 事件回来，不等执行结果）。
 *
 * @param item 菜单项
 */
function onMenuSelect(item: MenuItemVo): void {
  closeMenu()
  executeCommand(item.id).catch(e => console.warn('[HierarchyPanel] 命令执行失败', e))
}

function closeMenu(): void {
  menu.visible = false
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

onMounted(async () => {
  await subscribeEvent('scene.changed', onSceneChanged)
  loadScene()
})

onUnmounted(() => {
  disposed = true
  unsubscribeEvent('scene.changed', onSceneChanged)
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
