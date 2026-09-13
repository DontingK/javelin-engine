import { ref } from 'vue'
import type { Component, Ref } from 'vue'

import HierarchyPanel from '../panels/HierarchyPanel.vue'
import ProjectPanel from '../panels/ProjectPanel.vue'
import EntityInspectorPanel from '../panels/EntityInspectorPanel.vue'
import SceneViewportPanel from '../panels/SceneViewportPanel.vue'
import GameViewportPanel from '../panels/GameViewportPanel.vue'
import ConsolePanel from '../panels/ConsolePanel.vue'

/**
 * 停靠区面板数据。
 */
export interface DockPanel {
  /** 面板唯一标识 */
  id: string
  /** 标签页标题 */
  title: string
  /** 面板组件 */
  component: Component
  /** 是否允许关闭 */
  closable?: boolean
}

/** 停靠区名称。 */
export type DockContainerName = 'left' | 'right' | 'bottom' | 'center'

/**
 * 停靠区面板组件实例可暴露的生命周期方法。
 *
 * <p>{@link DockContainer} 在切换标签页时会调用这些方法，面板组件可通过
 * {@code defineExpose({ onShow, onHide })} 暴露。</p>
 */
export interface DockPanelExpose {
  /** 面板变为当前活动面板时触发 */
  onShow?: () => void
  /** 面板离开当前活动面板时触发 */
  onHide?: () => void
}

const leftPanels = ref<DockPanel[]>([
  { id: 'hierarchy', title: '层级', component: HierarchyPanel, closable: false }
])

const rightPanels = ref<DockPanel[]>([
  { id: 'inspector', title: '属性', component: EntityInspectorPanel, closable: true }
])

const bottomPanels = ref<DockPanel[]>([
  { id: 'project', title: '项目', component: ProjectPanel, closable: false },
  { id: 'console', title: '控制台', component: ConsolePanel, closable: true }
])

const centerPanels = ref<DockPanel[]>([
  { id: 'scene', title: '场景', component: SceneViewportPanel, closable: false },
  { id: 'game', title: '游戏', component: GameViewportPanel, closable: false }
])

const containers = {
  left: leftPanels,
  right: rightPanels,
  bottom: bottomPanels,
  center: centerPanels
} satisfies Record<DockContainerName, Ref<DockPanel[]>>

/**
 * 向指定停靠区添加面板。
 *
 * <p>若面板 ID 已存在，则替换原有面板。</p>
 *
 * @param container 目标停靠区名称
 * @param panel     要添加的面板
 */
function addPanel(container: DockContainerName, panel: DockPanel): void {
  const list = containers[container]
  const index = list.value.findIndex((p) => p.id === panel.id)
  if (index >= 0) {
    list.value = [...list.value.slice(0, index), panel, ...list.value.slice(index + 1)]
  } else {
    list.value = [...list.value, panel]
  }
}

/**
 * 从指定停靠区移除面板。
 *
 * @param container 目标停靠区名称
 * @param panelId   要移除的面板 ID
 */
function removePanel(container: DockContainerName, panelId: string): void {
  const list = containers[container]
  list.value = list.value.filter((p) => p.id !== panelId)
}

/**
 * 获取指定停靠区的面板列表。
 *
 * @param container 停靠区名称
 * @returns 响应式面板列表
 */
function getPanels(container: DockContainerName) {
  return containers[container]
}

/**
 * 编辑器布局组合式函数。
 *
 * <p>提供各停靠区面板列表及增删方法，同时向 {@code window.javelinEditorLayout} 注册全局 API，
 * 方便外部 JavaScript 直接调用。</p>
 */
export function useEditorLayout() {
  return {
    leftPanels,
    rightPanels,
    bottomPanels,
    centerPanels,
    addPanel,
    removePanel,
    getPanels
  }
}

if (typeof window !== 'undefined') {
  const win = window as unknown as Record<string, unknown>
  if (!win.javelin) {
    win.javelin = {}
  }
  ;(win.javelin as Record<string, unknown>).layout = {
    addPanel,
    removePanel,
    getPanels
  }
}
