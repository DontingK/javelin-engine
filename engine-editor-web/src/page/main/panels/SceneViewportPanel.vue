<template>
  <div ref="sceneViewport" class="scene-viewport"></div>
</template>

<script setup lang="ts">
import {onMounted, onUnmounted, ref} from "vue";
import {renderRectChange} from '../../../service/javelinService.ts'

let sceneViewport = ref<HTMLElement | null>(null)

/**
 * 场景视口占位组件。
 */

/**
 * 面板变为活动状态时触发。
 *
 * <p>通知后端切换到场景编辑模式。</p>
 */
function onShow(): void {
  // TODO: 后端接口对接（场景模式切换）
  console.log('[SceneViewportPanel] onShow - 待对接后端接口')
}

/**
 * 面板离开活动状态时触发。
 */
function onHide(): void {
  // noop
}

function onResize(): void {
  const ratio = window.devicePixelRatio || 1;
  const rect = sceneViewport.value?.getBoundingClientRect() as DOMRect;
  if (rect) {
    renderRectChange({
      x: Math.round(rect.x),
      y: Math.round(rect.y),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      dpi: ratio
    }).catch((error: unknown) => {
      console.error('[SceneViewportPanel] 通知渲染区域变化失败:', error)
    })
  }
}

// 创建观察器
const resizeObserver = new ResizeObserver(() => {
  onResize()
});
defineExpose({
  onShow,
  onHide
})

onMounted(() => {
  onResize()
  // 监听目标div
  resizeObserver.observe(sceneViewport.value as Element);
})
onUnmounted(() => {
  // 停止监听
  resizeObserver.disconnect();
})

</script>

<style scoped>
.scene-viewport {
  width: 100%;
  height: 100%;
}
</style>
