<template>
  <div class="dock-container">
    <div class="dock-header">
      <div class="dock-tabs">
        <button
          v-for="panel in panels"
          :key="panel.id"
          class="dock-tab"
          :class="{ active: activeId === panel.id }"
          @click="selectPanel(panel.id)"
        >
          <span class="tab-title">{{ panel.title }}</span>
          <span
            v-if="panel.closable !== false"
            class="tab-close"
            @click="closePanel($event, panel)"
          >×</span>
        </button>
      </div>
    </div>
    <div class="dock-content">
      <component :is="activePanel?.component" v-if="activePanel" ref="activeComponentRef" :panel="activePanel" />
      <div v-else class="empty-placeholder">无面板</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { type DockPanel, type DockPanelExpose } from '../composables/useEditorLayout.ts'

/**
 * 可复用停靠区容器。
 */

interface DockContainerProps {
  /** 停靠区标题，显示在标签栏左侧 */
  title?: string
  /** 停靠区内的面板列表 */
  panels: DockPanel[]
  /** 默认选中的面板 ID */
  defaultActiveId?: string
}

const props = defineProps<DockContainerProps>()
const emit = defineEmits<{
  (e: 'close', panelId: string): void
}>()

const activeId = ref<string>(props.defaultActiveId ?? (props.panels.length > 0 ? props.panels[0].id : ''))
const activeComponentRef = ref<DockPanelExpose | null>(null)

const activePanel = computed<DockPanel | undefined>(() =>
  props.panels.find((panel) => panel.id === activeId.value)
)

/**
 * 调用当前活动面板的 onShow 方法。
 */
function callActivePanelShow(): void {
  nextTick(() => {
    activeComponentRef.value?.onShow?.()
  })
}

/**
 * 调用当前活动面板的 onHide 方法。
 */
function callActivePanelHide(): void {
  activeComponentRef.value?.onHide?.()
}

watch(activeId, () => {
  callActivePanelHide()
  callActivePanelShow()
})

onMounted(() => {
  callActivePanelShow()
})

function selectPanel(id: string): void {
  activeId.value = id
}

function closePanel(event: MouseEvent, panel: DockPanel): void {
  event.stopPropagation()
  if (panel.closable === false) {
    return
  }
  emit('close', panel.id)
  if (activeId.value === panel.id && props.panels.length > 1) {
    const remaining = props.panels.filter((p) => p.id !== panel.id)
    activeId.value = remaining[0].id
  }
}
</script>

<style scoped>
.dock-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: var(--dock-min-width);
  min-height: var(--dock-min-height);
  background: var(--editor-bg);
  overflow: hidden;
}

.dock-header {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  height: 28px;
  background: var(--editor-surface);
  border-bottom: 1px solid var(--editor-border);
  padding: 0 6px;
  gap: 8px;
}

.dock-title {
  color: var(--editor-text-dim);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.dock-tabs {
  display: flex;
  flex: 1;
  gap: 2px;
  overflow: hidden;
}

.dock-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  height: 24px;
  border-radius: 4px 4px 0 0;
  color: var(--editor-text-dim);
  white-space: nowrap;
}

.dock-tab:hover {
  background: var(--editor-surface-light);
  color: var(--editor-text);
}

.dock-tab.active {
  background: var(--editor-bg);
  color: var(--editor-text-heading);
  border: 1px solid var(--editor-border);
  border-bottom-color: var(--editor-bg);
}

.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  font-size: 14px;
  line-height: 1;
}

.tab-close:hover {
  background: rgba(0, 0, 0, 0.12);
  color: var(--editor-text-heading);
}

.dock-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  position: relative;
}

.empty-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--editor-text-dim);
}
</style>
