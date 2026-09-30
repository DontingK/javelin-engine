import { callJava } from '../bridge/jcefBridge.ts'

/**
 * 菜单项 VO（对应 Java 侧 MenuItemVo）。
 *
 * <p>顶栏拉取时仅填 id/label。separator 为 true 时其余字段无效。</p>
 */
export interface MenuItemVo {
  /** 菜单 id（顶栏拉取）或命令 id（域拉取） */
  id: string
  /** 显示文本 */
  label: string
  /** 快捷键文本（可空，第一期仅显示） */
  shortcut?: string
  /** 分隔符项 */
  separator?: boolean
  /** 是否勾选命令 */
  checkable?: boolean
  /** 当前勾选态（仅 checkable 有意义） */
  checked?: boolean
  /** 置灰 */
  enabled?: boolean
  /** 子菜单（预留） */
  children?: MenuItemVo[]
}

/**
 * 拉取顶栏菜单列表（Java 侧 MenuRegistry 单源，声明式结构）。
 *
 * @returns 顶栏菜单数组（仅 id/label）
 */
export function listMenus(): Promise<MenuItemVo[]> {
  return callJava<MenuItemVo[]>('/menu/list', {})
}

/**
 * 弹出菜单（顶栏标签/面板右键共用，菜单渲染统一 Swing）：Java 侧 EDT 弹
 * Swing JPopupMenu（heavyweight popup 浮于视口 Canvas 之上，见设计档
 * Editor_Menu_Design §5）。
 *
 * @param menuId 菜单域 id（顶栏菜单 id 或右键域如 ctx.hierarchy）
 * @param x web 坐标 x（顶栏=标签左缘、右键=鼠标 x；web 坐标 = contentPane 坐标）
 * @param y web 坐标 y（顶栏=顶栏下缘、右键=鼠标 y）
 */
export function openMenu(menuId: string, x: number, y: number): Promise<void> {
  return callJava<void>('/menu/open', {menuId, x: Math.round(x), y: Math.round(y)})
}

/**
 * 执行命令（面板工具按钮等 Web 直触发入口用；Swing 菜单项由 Java 侧直执行不经此）。
 *
 * @param id 命令 id
 * @returns 是否找到并执行
 */
export function executeCommand(id: string): Promise<boolean> {
  return callJava<boolean>('/command/execute', {id})
}
