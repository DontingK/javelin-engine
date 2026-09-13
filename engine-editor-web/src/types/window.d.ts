import type { Component } from 'vue'

/**
 * 全局 window 对象扩展。
 *
 * <p>为编辑器全局 API 提供 TypeScript 类型提示。</p>
 */
declare global {
  /** 停靠区面板数据。 */
  interface DockPanel {
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
  type DockContainerName = 'left' | 'right' | 'bottom' | 'center'

  /** 项目信息。 */
  interface ProjectInfo {
    /** 项目唯一标识 */
    id: string
    /** 项目名称 */
    name: string
    /** 项目路径 */
    path: string
  }

  /** 编辑器全局 API。 */
  interface JavelinGlobalApi {
    /** 布局管理 API */
    layout: {
      /**
       * 向指定停靠区添加面板。
       *
       * @param container 目标停靠区名称
       * @param panel     要添加的面板
       */
      addPanel(container: DockContainerName, panel: DockPanel): void

      /**
       * 从指定停靠区移除面板。
       *
       * @param container 目标停靠区名称
       * @param panelId   要移除的面板 ID
       */
      removePanel(container: DockContainerName, panelId: string): void

      /**
       * 获取指定停靠区的面板列表。
       *
       * @param container 停靠区名称
       */
      getPanels(container: DockContainerName): { value: DockPanel[] }
    }

    /** 项目管理 API */
    project: {
      /**
       * 打开指定项目。
       *
       * @param project 项目信息
       */
      openProject(project: ProjectInfo): void

      /**
       * 关闭当前项目，返回项目选择页面。
       */
      closeProject(): void

      /** 当前项目，null 表示未打开项目 */
      currentProject: { value: ProjectInfo | null }
    }
  }

  interface Window {
    /** Javelin 编辑器全局 API */
    javelin: JavelinGlobalApi
  }
}

export {}
