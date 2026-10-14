<template>
  <div class="scene-panel">
    <div class="scene-toolbar">
      <div class="tool-group">
        <JeToolButton :active="activeTool === 'move'" title="移动工具" @click="selectTool('move')">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 2v20M2 12h20"/>
            <path d="M9 5l3-3 3 3M9 19l3 3 3-3M5 9l-3 3 3 3M19 9l3 3-3 3"/>
          </svg>
        </JeToolButton>
        <JeToolButton disabled title="旋转工具（未实现）">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/>
            <path d="M21 3v5h-5"/>
          </svg>
        </JeToolButton>
        <JeToolButton disabled title="缩放工具（未实现）">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
               stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 3h6v6M21 3l-7 7M9 21H3v-6M3 21l7-7"/>
          </svg>
        </JeToolButton>
      </div>
      <div class="toolbar-separator"></div>
      <div class="tool-group">
        <JeToolButton :active="space === 'local'" title="局部坐标（Gizmo 跟随目标局部轴）" @click="selectSpace('local')">
          局部
        </JeToolButton>
        <JeToolButton :active="space === 'world'" title="世界坐标（Gizmo 恒世界轴）" @click="selectSpace('world')">
          世界
        </JeToolButton>
      </div>
    </div>
    <div ref="sceneViewport" class="scene-viewport"></div>
  </div>
</template>

<script setup lang="ts">
import {ref} from "vue";
import {setSceneModel} from '../../../service/sceneService.ts'
import {setGizmoSpace, setTransformTool} from '../../../service/gizmoService.ts'
import type {GizmoSpace, TransformTool} from '../../../service/gizmoService.ts'
import {useRenderRectReport} from '../composables/useRenderRectReport.ts'
import JeToolButton from '../../../components/common/JeToolButton.vue'

let sceneViewport = ref<HTMLElement | null>(null)

/**
 * 场景视口面板：顶部工具栏（Tools 组 + 坐标切换）+ 视口占位。
 *
 * <p>视口 rect 上报只测视口占位 div——工具栏占位后 Canvas 挖洞矩形自动下移
 * （ResizeObserver 重报兜底）。工具/坐标为前端本地态即点即亮（fire-and-forget，
 * 对齐 setModel 风格），后端经 /gizmo/setTool、/gizmo/setSpace 意图镜像帧内
 * 消费；不持久化（重启回默认 移动+世界）。</p>
 */

/** 当前工具（后端唯一实现=move；旋转/缩放按钮置灰占位） */
let activeTool = ref<TransformTool>('move')

/** 当前坐标空间（默认 world=现状语义；仅影响 Gizmo 朝向与拖拽轴） */
let space = ref<GizmoSpace>('world')

/**
 * 工具切换：本地态即亮 + 桥上报（Java 侧解析查名落空维持现状）。
 */
function selectTool(tool: TransformTool): void {
  if (activeTool.value === tool) {
    return
  }
  activeTool.value = tool
  setTransformTool(tool).catch((error: unknown) => {
    console.error('[SceneViewportPanel] 切换工具失败:', error)
  })
}

/**
 * 坐标空间切换：本地态即亮 + 桥上报（Java 侧 volatile 直写、update 每帧读取）。
 */
function selectSpace(target: GizmoSpace): void {
  if (space.value === target) {
    return
  }
  space.value = target
  setGizmoSpace(target).catch((error: unknown) => {
    console.error('[SceneViewportPanel] 切换坐标空间失败:', error)
  })
}

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
.scene-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
}

.scene-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 24px;
  padding: 0 2px;
  flex-shrink: 0;
  background: var(--editor-surface);
  border-bottom: 1px solid var(--editor-border);
}

.tool-group {
  display: flex;
  align-items: center;
  border: 1px solid var(--editor-border);
  background: var(--editor-surface-light);
}

.toolbar-separator {
  width: 1px;
  height: 14px;
  margin: 0 2px;
  background: var(--editor-border);
}

.scene-viewport {
  flex: 1;
  min-height: 0;
}
</style>
