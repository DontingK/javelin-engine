import { callJava } from '../bridge/jcefBridge.ts'

/**
 * 渲染区域参数。
 */
export interface RectPar {
  x: number
  y: number
  width: number
  height: number
  dpi: number
}

/**
 * 通知后端渲染区域变化。
 *
 * <p>当场景视口大小或位置变化时调用，后端根据此信息调整 Vulkan 渲染区域。</p>
 *
 * @param rectPar 渲染区域参数
 */
export function renderRectChange(rectPar: RectPar): Promise<void> {
  return callJava<void>('/javelin/render/rce', rectPar)
}
