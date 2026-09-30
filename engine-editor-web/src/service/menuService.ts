import { callJava } from '../bridge/jcefBridge.ts'

/**
 * 菜单项 VO（对应 Java 侧 MenuItemVo）。
 *
 * <p>顶栏拉取时仅填 id/label；右键域拉取时全量即时求值。
 * separator 为 true 时其余字段无效。</p>
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
 * 拉取指定菜单域结构（右键域用：含即时求值的 enabled/checked，
 * 每次右键重新拉取，状态天然新鲜）。
 *
 * @param menuId 菜单域 id（如 ctx.hierarchy）
 * @returns 菜单项数组
 */
export function listMenuItems(menuId: string): Promise<MenuItemVo[]> {
  return callJava<MenuItemVo[]>('/menu/list', {menuId})
}

/**
 * 弹出顶栏菜单：Java 侧换算坐标后弹 Swing JPopupMenu
 * （heavyweight popup 浮于视口 Canvas 之上，见设计档 Editor_Menu_Design §5.1）。
 *
 * @param menuId 菜单域 id
 * @param x web 坐标 x（标签左缘；web 坐标 = contentPane 坐标）
 * @param y web 坐标 y（顶栏下缘）
 */
export function openMenu(menuId: string, x: number, y: number): Promise<void> {
  return callJava<void>('/menu/open', {menuId, x: Math.round(x), y: Math.round(y)})
}

/**
 * 执行命令（右键菜单/按钮共用入口）。
 *
 * @param id 命令 id
 * @returns 是否找到并执行
 */
export function executeCommand(id: string): Promise<boolean> {
  return callJava<boolean>('/command/execute', {id})
}
