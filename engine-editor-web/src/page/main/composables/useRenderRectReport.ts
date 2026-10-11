import {onMounted, onUnmounted} from 'vue'
import type {Ref} from 'vue'

import {renderRectChange} from '../../../service/javelinService.ts'

/**
 * 视口渲染矩形上报（ResizeObserver → renderRectChange）。
 *
 * <p>Scene/Game 两个视口面板共用：面板根 div 尺寸变化即上报矩形，
 * Java 侧据此重定位/缩放原生画布。</p>
 *
 * <p><b>每个视口面板都必须各自接入</b>：DockContainer 换标签页 = 组件卸载/
 * 重挂载，同一时刻只有活动面板的观察者在监听（Game 页 resize 无人上报的
 * 根因即此）。observe 挂载即回调一次 + onMounted 显式上报——标签页切换
 * 重挂载时画布矩形自愈。</p>
 *
 * @param element 面板根元素 ref
 */
export function useRenderRectReport(element: Ref<HTMLElement | null>): void {
  function onResize(): void {
    const ratio = window.devicePixelRatio || 1
    const rect = element.value?.getBoundingClientRect() as DOMRect
    if (rect) {
      renderRectChange({
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        dpi: ratio
      }).catch((error: unknown) => {
        console.error('[useRenderRectReport] 通知渲染区域变化失败:', error)
      })
    }
  }

  const resizeObserver = new ResizeObserver(() => {
    onResize()
  })

  onMounted(() => {
    onResize()
    if (element.value) {
      resizeObserver.observe(element.value)
    }
  })
  onUnmounted(() => {
    resizeObserver.disconnect()
  })
}
