<template>
  <div class="panel project-panel">
    <SplitPanel direction="horizontal" :initial-size="LEFT_WIDTH" :min-size="MIN_LEFT_WIDTH">
      <template #first>
        <div class="project-left">
          <JavelinTree
              v-if="dirTree.length > 0"
              :data="dirTree"
              node-key="path"
              label-key="name"
              children-key="children"
              :selected-key="selectedDirPath"
              @select="onSelectDir"
              @contextmenu="onTreeContextMenu"
          >
            <template #icon="{ node }">
              <img
                  class="project-node-icon"
                  :src="getTreeNodeIcon(node)"
                  alt=""
              />
            </template>
          </JavelinTree>
          <div v-else class="empty-tip">项目文件树加载中…</div>
        </div>
      </template>
      <template #second>
        <div class="project-right">
          <div class="project-breadcrumb">
            <template v-for="(segment, index) in breadcrumbSegments" :key="segment.path">
              <button
                  class="breadcrumb-segment"
                  @click="selectedDirPath = segment.path"
              >{{ segment.name }}</button>
              <span v-if="index < breadcrumbSegments.length - 1" class="breadcrumb-sep">/</span>
            </template>
          </div>
          <div class="project-entries" @contextmenu.prevent="onBlankContextMenu">
            <div
                v-for="entry in entries"
                :key="entry.path"
                class="project-entry"
                :class="{ 'project-entry--selected': entrySelectedPath === entry.path }"
                @click="onEntryClick(entry)"
                @dblclick="onEntryDoubleClick(entry)"
                @contextmenu.prevent="onEntryContextMenu(entry, $event)"
            >
              <img class="project-node-icon" :src="getEntryIcon(entry)" alt="" />
              <span class="project-entry__label">{{ entry.name }}</span>
            </div>
            <div v-if="entries.length === 0" class="empty-tip">空目录</div>
          </div>
        </div>
      </template>
    </SplitPanel>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import JavelinTree from '../../../components/common/tree/JavelinTree.vue'
import SplitPanel from '../../../components/common/SplitPanel.vue'
import {loadProjectTree, selectProjectEntry, type ProjectTreeNodeVo} from '../../../service/projectService.ts'
import {openMenu} from '../../../service/menuService.ts'
import type {TreeNode} from '../../../components/common/types.ts'

/** 图标映射（public/icon，IDEA 项目视图口径） */
const ICONS = {
  resourcesRoot: '/icon/resourcesRoot.svg',
  sourceRoot: '/icon/sourceRoot.svg',
  javaPackage: '/icon/package.svg',
  folder: '/icon/folder.svg',
  javaClass: '/icon/class.svg',
  json: '/icon/json.svg',
  unknown: '/icon/unknown.svg'
} as const

/** 左树初始宽度与最小宽度（可拖拽调宽） */
const LEFT_WIDTH = 180
const MIN_LEFT_WIDTH = 100

/**
 * 项目面板（Unity Project 窗口布局）。
 *
 * <p>左树仅目录、右侧显示选中目录的直接子项（目录优先 + 名称序），面包屑点击
 * 沿路径逐级跳转、右区目录双击进入（Unity 口径）。数据源 /project/tree 全量树
 * 快照（Java 侧桥线程纯 IO 遍历，隐藏项与伴生 .meta 已过滤；ready=false 时节流
 * 重试兜底启动竞态）；左树=目录投影、右区=选中目录 children 的纯前端切面，无
 * 二次拉取。选中经 /project/select 上报 Java 侧 EditorSelection 单源
 * （project 域菜单命令的上下文源）：单击 fire-and-forget、右键 await 落上报后
 * 再 openMenu（Swing 菜单 enabled 求值读到新选中态，防读到旧态）；三个右键
 * 入口：左树目录/右区条目/右区空白（空白=当前目录语境），菜单项点击在 Java
 * 侧直执行命令。图标映射（IDEA 项目视图口径）：Assets=resourcesRoot、
 * src/src/main/java=sourceRoot、src/main/java 子目录=package、其余目录=folder、
 * .java=class、.json=json、未知类型=unknown。</p>
 */
const treeData = ref<ProjectTreeNodeVo[]>([])
/** 左树选中目录（相对项目根路径，兼右区内容源锚点） */
const selectedDirPath = ref<string | undefined>(undefined)
/** 右区条目选中（本地高亮；目录双击进入时清空） */
const entrySelectedPath = ref<string | undefined>(undefined)

/** 编辑器未就绪重试（启动竞态：引擎装配可能晚于页面挂载，对齐层级面板口径） */
const MAX_RETRY = 30
const RETRY_DELAY_MS = 500
let retryCount = 0
let disposed = false

/** 左树数据：全量树 → 目录投影（文件不出左树） */
const dirTree = computed<ProjectTreeNodeVo[]>(() => projectDirs(treeData.value))

/** 右区内容：选中目录的直接子项（Java 侧已排目录优先） */
const entries = computed<ProjectTreeNodeVo[]>(() => {
  if (selectedDirPath.value == null) {
    return []
  }
  return findNode(treeData.value, selectedDirPath.value)?.children ?? []
})

/** 面包屑段（根 → 选中目录逐级；点击段跳转对应目录） */
const breadcrumbSegments = computed<{ path: string, name: string }[]>(() => {
  if (selectedDirPath.value == null) {
    return []
  }
  const segments: { path: string, name: string }[] = []
  let acc = ''
  for (const name of selectedDirPath.value.split('/')) {
    acc = acc === '' ? name : `${acc}/${name}`
    segments.push({path: acc, name})
  }
  return segments
})

/**
 * 拉取项目文件树快照；选中目录失效（缺路径首次进入/树刷新后不存在）回默认根。
 */
async function loadTree(): Promise<void> {
  if (disposed) {
    return
  }
  try {
    const vo = await loadProjectTree()
    if (!vo.ready) {
      retryOrFail('项目未就绪')
      return
    }
    retryCount = 0
    treeData.value = vo.roots ?? []
    ensureSelection()
  } catch (e) {
    retryOrFail(`拉取项目文件树失败: ${e}`)
  }
}

/** 选中目录兜底：无效则取 Assets（缺失取首根，面板不留空选中） */
function ensureSelection(): void {
  const current = selectedDirPath.value
  if (current != null && findNode(treeData.value, current) != null) {
    return
  }
  const assets = findNode(treeData.value, 'Assets')
  selectedDirPath.value = assets != null ? assets.path : treeData.value[0]?.path
}

/**
 * 节流重试，达上限放弃并记警告。
 *
 * @param reason 本次失败原因（日志用）
 */
function retryOrFail(reason: string): void {
  if (retryCount++ < MAX_RETRY) {
    setTimeout(loadTree, RETRY_DELAY_MS)
  } else {
    console.warn(`[ProjectPanel] ${reason}，已达重试上限（${MAX_RETRY} 次）`)
  }
}

/**
 * 全量树 → 仅目录投影（浅拷贝节点、children 换投影结果；右区查原始树不受影响）。
 *
 * @param nodes 原始节点层
 */
function projectDirs(nodes: ProjectTreeNodeVo[]): ProjectTreeNodeVo[] {
  const dirs: ProjectTreeNodeVo[] = []
  for (const node of nodes) {
    if (!node.directory) {
      continue
    }
    dirs.push({...node, children: projectDirs(node.children ?? [])})
  }
  return dirs
}

/** 沿树 DFS 按相对路径找节点（未命中 null） */
function findNode(nodes: ProjectTreeNodeVo[], path: string): ProjectTreeNodeVo | null {
  for (const node of nodes) {
    if (node.path === path) {
      return node
    }
    const hit = findNode(node.children ?? [], path)
    if (hit != null) {
      return hit
    }
  }
  return null
}

/**
 * 左树单击目录：选中即切换右区内容（Unity 口径：单列目录树选中驱动内容区）。
 *
 * @param node 当前目录节点
 */
function onSelectDir(node: TreeNode): void {
  const path = (node as ProjectTreeNodeVo).path
  selectedDirPath.value = path == null ? undefined : path
  entrySelectedPath.value = undefined
  reportSelection(path)
}

/**
 * 左树图标插槽适配（icon 槽 node 收窄为 TreeNode——path/directory 经原树
 * 投影保证存在，script 内断言收口）。
 *
 * @param node 目录树节点
 */
function getTreeNodeIcon(node: TreeNode): string {
  return getDirIcon((node as ProjectTreeNodeVo).path ?? '')
}

/**
 * 右区条目单击：本地高亮选中。
 *
 * @param entry 目标条目
 */
function onEntryClick(entry: ProjectTreeNodeVo): void {
  entrySelectedPath.value = entry.path
  reportSelection(entry.path)
}

/**
 * 右区条目双击：目录进入（等同左树选中该目录），文件暂无动作（打开管线后置）。
 *
 * @param entry 目标条目
 */
function onEntryDoubleClick(entry: ProjectTreeNodeVo): void {
  if (!entry.directory) {
    return
  }
  selectedDirPath.value = entry.path
  entrySelectedPath.value = undefined
  reportSelection(entry.path)
}

/**
 * 单击选中上报（fire-and-forget，本地高亮即时；右键路径独立走 await 版）。
 *
 * @param path 选中路径
 */
function reportSelection(path: string | null): void {
  selectProjectEntry(path).catch(e => console.warn('[ProjectPanel] 选中上报失败', e))
}

/**
 * 右键弹菜单共用链：await 落选中上报后再 /menu/open 传 ctx.project 域 id +
 * 鼠标坐标（web 坐标 = contentPane 坐标，同层级面板口径；菜单渲染统一 Swing，
 * 设计档 Editor_Menu_Design §5）。
 *
 * @param path  右键目标路径（已本地选中）
 * @param event 鼠标事件
 */
async function openProjectMenu(path: string | null, event: MouseEvent): Promise<void> {
  try {
    await selectProjectEntry(path)
    await openMenu('ctx.project', event.clientX, event.clientY)
  } catch (e) {
    console.warn('[ProjectPanel] 右键菜单链路失败', e)
  }
}

/**
 * 左树目录右键：右键即选中（右区同步切内容）后弹菜单。
 *
 * @param node  目录节点
 * @param event 鼠标事件
 */
function onTreeContextMenu(node: TreeNode, event: MouseEvent): void {
  const path = (node as ProjectTreeNodeVo).path
  selectedDirPath.value = path == null ? undefined : path
  entrySelectedPath.value = undefined
  openProjectMenu(path ?? null, event)
}

/**
 * 右区条目右键：右键即选中后弹菜单（stopPropagation 防冒泡到空白入口）。
 *
 * @param entry 目标条目
 * @param event 鼠标事件
 */
function onEntryContextMenu(entry: ProjectTreeNodeVo, event: MouseEvent): void {
  event.stopPropagation()
  entrySelectedPath.value = entry.path
  openProjectMenu(entry.path, event)
}

/**
 * 右区空白右键：当前目录语境弹菜单（树未加载无选中则忽略）。
 *
 * @param event 鼠标事件
 */
function onBlankContextMenu(event: MouseEvent): void {
  if (selectedDirPath.value == null) {
    return
  }
  openProjectMenu(selectedDirPath.value, event)
}

/**
 * 目录图标裁决（路径规则见组件文档注释）。
 *
 * @param path 目录相对路径
 */
function getDirIcon(path: string): string {
  if (path === 'Assets') {
    return ICONS.resourcesRoot
  }
  if (path === 'src' || path === 'src/main/java') {
    return ICONS.sourceRoot
  }
  if (path.startsWith('src/main/java/')) {
    return ICONS.javaPackage
  }
  return ICONS.folder
}

/**
 * 条目图标裁决（目录走路径规则，文件按扩展名）。
 *
 * @param entry 目标条目
 */
function getEntryIcon(entry: ProjectTreeNodeVo): string {
  if (entry.directory) {
    return getDirIcon(entry.path)
  }
  const name = entry.name
  const dot = name.lastIndexOf('.')
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
  if (ext === 'java') {
    return ICONS.javaClass
  }
  if (ext === 'json') {
    return ICONS.json
  }
  return ICONS.unknown
}

// 选中目录被外部路径（面包屑跳转/双击进入）改写时清右区条目高亮，
// 防止上一目录的选中残留到新内容区
watch(selectedDirPath, () => {
  entrySelectedPath.value = undefined
})

onMounted(() => {
  loadTree()
})

onUnmounted(() => {
  disposed = true
})
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 2px;
}

.project-left {
  height: 100%;
  overflow: auto;
}

.project-right {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.project-breadcrumb {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  height: 20px;
  padding: 0 2px;
  gap: 2px;
  border-bottom: 1px solid var(--editor-border);
  overflow: hidden;
  white-space: nowrap;
}

.breadcrumb-segment {
  font-size: 12px;
  color: var(--editor-text-dim);
  padding: 0 2px;
}

.breadcrumb-segment:hover {
  color: var(--editor-text);
}

.breadcrumb-sep {
  color: var(--editor-text-dim);
  font-size: 12px;
}

.project-entries {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 2px;
}

.project-entry {
  display: flex;
  align-items: center;
  gap: 2px;
  width: max-content;
  min-width: 100%;
  padding: 1px 4px;
  border-radius: 3px;
  cursor: default;
  user-select: none;
}

.project-entry:hover {
  background: var(--editor-surface-light);
}

/* 选中行 = accent 蓝底白字（Unity 选中高亮口径）；并集选择器压过 :hover 灰底 */
.project-entry--selected,
.project-entry--selected:hover {
  background: var(--editor-accent);
  color: #fff;
}

.project-entry__label {
  flex: 0 0 auto;
  white-space: nowrap;
}

.project-node-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.empty-tip {
  color: var(--editor-text-dim);
  font-size: 12px;
  text-align: center;
  margin-top: 16px;
}
</style>
