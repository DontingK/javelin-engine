<template>
  <div ref="gameViewport" class="scene-viewport"></div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {setSceneModel} from '../../../service/sceneService.ts'
import {useRenderRectReport} from '../composables/useRenderRectReport.ts'

/**
 * 游戏视口占位组件。
 */

let gameViewport = ref<HTMLElement | null>(null)

useRenderRectReport(gameViewport)

/**
 * 面板变为活动状态时触发。
 *
 * <p>通知后端切换到 GAME 模式：视口改拉用户场景相机与物体，
 * 编辑器相机/网格/Gizmo 不进画面。</p>
 */
function onShow(): void {
  setSceneModel('GAME').catch((error: unknown) => {
    console.error('[GameViewportPanel] 切换 GAME 模式失败:', error)
  })
}

/**
 * 面板离开活动状态时触发。
 */
function onHide(): void {
  // noop
}

defineExpose({
  onShow,
  onHide
})
</script>

<style scoped>
.scene-viewport {
  width: 100%;
  height: 100%;
}
</style>
