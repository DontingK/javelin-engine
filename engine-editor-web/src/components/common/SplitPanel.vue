<template>
  <div
    class="split-panel"
    :class="direction"
    :style="containerStyle"
  >
    <!-- 第一面板 -->
    <div
      class="panel panel-first"
      :style="firstPanelStyle"
    >
      <slot name="first" />
    </div>

    <!-- 拖拽分隔条 -->
    <Resizer
      :direction="resizerDirection"
      @resize-start="onResizeStart"
      @resize="onResize"
      @resize-end="onResizeEnd"
    />

    <!-- 第二面板 -->
    <div
      class="panel panel-second"
      :style="secondPanelStyle"
    >
      <slot name="second" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import Resizer from './Resizer.vue'

/**
 * 分割面板组件。
 *
 * <p>支持水平和垂直两个方向的分割，通过拖拽分隔条调整两侧面板的大小。</p>
 */

interface SplitPanelProps {
  /** 分割方向：horizontal 为左右分割，vertical 为上下分割 */
  direction: 'horizontal' | 'vertical'
  /** 第一面板的初始大小（像素） */
  initialSize?: number
  /** 第一面板的最小大小（像素），默认 0 */
  minSize?: number
  /** 第一面板的最大大小（像素），默认 Infinity */
  maxSize?: number
  /** 为 true 时，initialSize 控制第二面板的大小（从右侧/底部计算） */
  reverse?: boolean
}

const props = withDefaults(defineProps<SplitPanelProps>(), {
  initialSize: 200,
  minSize: 0,
  maxSize: Infinity,
  reverse: false,
})

const currentSize = ref(props.initialSize)

watch(() => props.initialSize, (newSize) => {
  if (!isResizing.value) {
    currentSize.value = newSize
  }
})
const isResizing = ref(false)

const resizerDirection = computed(() => props.direction)

const containerStyle = computed(() => {
  if (props.direction === 'horizontal') {
    return { flexDirection: 'row' as const }
  }
  return { flexDirection: 'column' as const }
})

const firstPanelStyle = computed(() => {
  if (props.reverse) {
    if (props.direction === 'horizontal') {
      return {
        width: '0',
        height: '100%',
        flexGrow: 1,
      }
    }
    return {
      width: '100%',
      height: '0',
      flexGrow: 1,
    }
  }
  if (props.direction === 'horizontal') {
    return {
      width: `${currentSize.value}px`,
      height: '100%',
    }
  }
  return {
    width: '100%',
    height: `${currentSize.value}px`,
  }
})

const secondPanelStyle = computed(() => {
  if (props.reverse) {
    if (props.direction === 'horizontal') {
      return {
        width: `${currentSize.value}px`,
        height: '100%',
      }
    }
    return {
      width: '100%',
      height: `${currentSize.value}px`,
    }
  }
  if (props.direction === 'horizontal') {
    return {
      width: '0',
      height: '100%',
      flexGrow: 1,
    }
  }
  return {
    width: '100%',
    height: '0',
    flexGrow: 1,
  }
})

let resizeStartSize = 0

function onResizeStart(): void {
  isResizing.value = true
  resizeStartSize = currentSize.value
}

function onResize(delta: number): void {
  const actualDelta = props.reverse ? -delta : delta
  const newSize = resizeStartSize + actualDelta
  currentSize.value = Math.max(props.minSize, Math.min(props.maxSize, newSize))
}

function onResizeEnd(): void {
  isResizing.value = false
}
</script>

<style scoped>
.split-panel {
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.panel {
  overflow: auto;
  position: relative;
}

.panel-first {
  flex-shrink: 0;
}

.panel-second {
  flex-shrink: 1;
}
</style>
