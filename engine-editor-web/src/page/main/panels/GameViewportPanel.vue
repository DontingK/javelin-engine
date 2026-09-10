<template>
  <div class="scene-viewport"></div>
</template>

<script setup lang="ts">
/**
 * 游戏视口占位组件。
 */

/**
 * 面板变为活动状态时触发。
 *
 * <p>通知后端切换到游戏运行模式。</p>
 */
function onShow(): void {
  // TODO: 后端接口对接
  console.log('[GameViewportPanel] onShow - 待对接后端接口')
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
  /* 与 Java 端 JcefBgfxBridge 透明色 BGR = (0,0,255) 对应，CSS 使用 RGB 顺序，纯红色 */
  background: rgb(255, 0, 0);
}
</style>
