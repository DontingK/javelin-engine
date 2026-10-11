<template>
  <div ref="sceneViewport" class="scene-viewport"></div>
</template>

<script setup lang="ts">
import {ref} from "vue";
import {setSceneModel} from '../../../service/sceneService.ts'
import {useRenderRectReport} from '../composables/useRenderRectReport.ts'

let sceneViewport = ref<HTMLElement | null>(null)

/**
 * 场景视口占位组件。
 */

useRenderRectReport(sceneViewport)

/**
 * 面板变为活动状态时触发。
 *
 * <p>通知后端切换到 SCENE 模式（恢复编辑器相机与编辑辅助对象渲染）。</p>
 */
function onShow(): void {
  setSceneModel('SCENE').catch((error: unknown) => {
    console.error('[SceneViewportPanel] 切换 SCENE 模式失败:', error)
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
