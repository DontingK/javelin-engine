import { executeCommand } from './menuService.ts'

/**
 * 全局快捷键服务（前端直接绑定，2026-10-02 拍板）。
 *
 * <p>绑定表前端静态定义（不走菜单结构拉取——零竞态零解析），window keydown
 * （capture 阶段）匹配后 preventDefault（阻断 CEF 浏览器默认行为，如 Ctrl+S
 * 保存网页对话框）并经 /command/execute 直触发命令。新命令的快捷键在绑定表
 * 加一行（命令本体/菜单显示仍归 Java 侧单源）。</p>
 *
 * <p>覆盖范围：焦点在 Web 面板（CEF 浏览器）内。焦点在视口 Canvas（AWT）时
 * 键盘事件进引擎输入系统不经 Web 层——引擎侧全局接入为开放项（设计档
 * Editor_Menu_Design §9）。文本输入目标（input/textarea/contentEditable）与
 * 按键重复（e.repeat）不拦截；命令未注册/置灰时执行为安全 no-op。</p>
 */

/** 快捷键绑定项 */
interface ShortcutBinding {
  ctrl: boolean
  shift: boolean
  alt: boolean
  /** 主键（小写；Del/Delete 等以 DOM 事件键名归一后比较） */
  key: string
  commandId: string
}

/** 绑定表（前端静态定义；Ctrl 语义兼容 macOS Command/metaKey） */
const BINDINGS: ShortcutBinding[] = [
  {ctrl: true, shift: false, alt: false, key: 's', commandId: 'file.save'},
]

/**
 * 初始化全局快捷键（主页面挂载时调用一次）。
 */
export function initGlobalShortcuts(): void {
  window.addEventListener('keydown', onKeyDown, true)
}

function onKeyDown(event: KeyboardEvent): void {
  if (event.repeat) {
    return
  }
  // 文本输入目标不拦截（树内重命名输入框内按 Ctrl+S 应为输入默认行为）
  const target = event.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
    return
  }
  const ctrl = event.ctrlKey || event.metaKey
  for (const binding of BINDINGS) {
    if (ctrl === binding.ctrl && event.shiftKey === binding.shift && event.altKey === binding.alt
        && keyMatches(binding.key, event.key)) {
      // 阻断 CEF 浏览器默认行为与后续监听
      event.preventDefault()
      event.stopPropagation()
      executeCommand(binding.commandId).catch(() => {
      })
      return
    }
  }
}

function keyMatches(bindingKey: string, eventKey: string): boolean {
  const key = eventKey.toLowerCase()
  if (key === bindingKey) {
    return true
  }
  // 键名别名归一（绑定表用 del，DOM 事件为 delete）
  return (key === 'delete' && bindingKey === 'del') || (key === 'esc' && bindingKey === 'esc')
}
