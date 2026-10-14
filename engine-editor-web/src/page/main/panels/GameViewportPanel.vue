<template>
  <div class="game-panel">
    <div class="game-toolbar"></div>
    <div ref="gameViewport" class="game-viewport"></div>
  </div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {setSceneModel} from '../../../service/sceneService.ts'
import {useRenderRectReport} from '../composables/useRenderRectReport.ts'

/**
 * 游戏视口占位组件。
 *
 * <p>顶部工具栏为空占位（高度/视觉与场景视图工具栏一致，内容留待 Game 视图
 * 工具设计——Unity 口径为显示比例/缩放/Stats 一族）；视口 rect 上报只测视口
 * 占位 div，Canvas 挖洞矩形自动让位。</p>
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
.game-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
}

.game-toolbar {
  display: flex;
  align-items: center;
  height: 24px;
  padding: 0 2px;
  flex-shrink: 0;
  background: var(--editor-surface);
  border-bottom: 1px solid var(--editor-border);
}

.game-viewport {
  flex: 1;
  min-height: 0;
}
</style>
