<template>
  <div class="editor-layout">
    <MenuBar />

    <div class="editor-body">
      <SplitPanel
        direction="horizontal"
        :initial-size="rightWidth"
        :min-size="MIN_DOCK_SIZE"
        :max-size="MAX_RIGHT_WIDTH"
        reverse
      >
        <template #first>
          <SplitPanel
            direction="vertical"
            :initial-size="bottomHeight"
            :min-size="MIN_DOCK_SIZE"
            :max-size="MAX_BOTTOM_HEIGHT"
            reverse
          >
            <template #first>
              <SplitPanel
                direction="horizontal"
                :initial-size="leftWidth"
                :min-size="MIN_DOCK_SIZE"
                :max-size="MAX_LEFT_WIDTH"
              >
                <template #first>
                  <DockContainer
                    class="left-dock"
                    :panels="leftPanels"
                    @close="onCloseLeft"
                  />
                </template>
                <template #second>
                  <DockContainer
                    class="center-dock"
                    :panels="centerPanels"
                    default-active-id="scene"
                    @close="onCloseCenter"
                  />
                </template>
              </SplitPanel>
            </template>
            <template #second>
              <DockContainer
                class="bottom-dock"
                :panels="bottomPanels"
                @close="onCloseBottom"
              />
            </template>
          </SplitPanel>
        </template>
        <template #second>
          <DockContainer
            class="right-dock"
            :panels="rightPanels"
            @close="onCloseRight"
          />
        </template>
      </SplitPanel>
    </div>

    <StatusBar />
  </div>
</template>

<script setup lang="ts">
import MenuBar from './layout/MenuBar.vue'
import StatusBar from './layout/StatusBar.vue'
import DockContainer from './layout/DockContainer.vue'
import SplitPanel from '../../components/common/SplitPanel.vue'
import { useEditorLayout, type DockContainerName } from './composables/useEditorLayout.ts'

/**
 * 编辑器布局页面。
 */

const MIN_DOCK_SIZE = 120
const MAX_LEFT_WIDTH = 600
const MAX_RIGHT_WIDTH = 600
const MAX_BOTTOM_HEIGHT = 500

const leftWidth = 260
const rightWidth = 300
const bottomHeight = 120

const {
  leftPanels,
  rightPanels,
  bottomPanels,
  centerPanels,
  removePanel
} = useEditorLayout()

function createCloseHandler(container: DockContainerName): (panelId: string) => void {
  return (panelId: string) => removePanel(container, panelId)
}

const onCloseLeft = createCloseHandler('left')
const onCloseRight = createCloseHandler('right')
const onCloseBottom = createCloseHandler('bottom')
const onCloseCenter = createCloseHandler('center')
</script>

<style scoped>
.editor-layout {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: var(--editor-bg);
  overflow: hidden;
}

.editor-body {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.left-dock,
.right-dock,
.bottom-dock {
  flex-shrink: 0;
}

.center-dock {
  flex: 1;
  min-width: var(--dock-min-width);
}
</style>
