<template>
  <div
    class="resizer"
    :class="direction"
    @pointerenter="onPointerEnter"
    @pointerleave="onPointerLeave"
    @pointerdown="onPointerDown"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue'

/**
 * 可拖拽分隔条。
 *
 * <p>支持水平和垂直两个方向，用于调整相邻停靠区的尺寸。拖拽时以鼠标按下位置为基准发送累计偏移量，
 * 父组件根据累计偏移量计算新的面板大小，避免边界钳制后鼠标回移导致分割线立即跳动。</p>
 */

interface ResizerProps {
  /** 分隔条方向：horizontal 表示左右分隔，vertical 表示上下分隔 */
  direction: 'horizontal' | 'vertical'
}

const props = defineProps<ResizerProps>()
const emit = defineEmits<{
  (e: 'resize-start'): void
  (e: 'resize', delta: number): void
  (e: 'resize-end'): void
}>()

const isDragging = ref(false)
let startX = 0
let startY = 0

function onPointerEnter(): void {

}

function onPointerLeave(): void {
  if (!isDragging.value) {
    // TODO: 后端接口对接（光标设置）
    console.log('[Resizer] onPointerLeave - 待对接后端光标接口')
  }
}

function onPointerDown(event: PointerEvent): void {
  isDragging.value = true
  startX = event.clientX
  startY = event.clientY
  // TODO: 后端接口对接（光标设置）
  console.log('[Resizer] onPointerDown - 待对接后端光标接口, cursor:', props.direction === 'horizontal' ? 'col-resize' : 'row-resize')
  document.body.style.userSelect = 'none'
  emit('resize-start')

  const target = event.currentTarget as HTMLElement | null
  if (target != null) {
    target.setPointerCapture(event.pointerId)
  }

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
  window.addEventListener('blur', onPointerUp)
}

function onPointerMove(event: PointerEvent): void {
  if (!isDragging.value) {
    return
  }
  const deltaX = event.clientX - startX
  const deltaY = event.clientY - startY

  if (props.direction === 'horizontal') {
    emit('resize', deltaX)
  } else {
    emit('resize', deltaY)
  }
}

function onPointerUp(): void {
  if (!isDragging.value) {
    return
  }
  isDragging.value = false
  // TODO: 后端接口对接（光标设置）
  console.log('[Resizer] onPointerUp - 待对接后端光标接口')
  document.body.style.userSelect = ''
  emit('resize-end')
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  window.removeEventListener('blur', onPointerUp)
}
</script>

<style scoped>
.resizer {
  position: relative;
  flex-shrink: 0;
  background: transparent;
  transition: background 0.15s;
}

.resizer::after {
  content: '';
  position: absolute;
  background: var(--editor-border);
  transition: background 0.15s;
}

.resizer.horizontal {
  width: var(--resizer-size);
  height: 100%;
}

.resizer.horizontal::after {
  top: 0;
  left: 0;
  width: var(--resizer-size);
  height: 100%;
}

.resizer.vertical {
  width: 100%;
  height: var(--resizer-size);
}

.resizer.vertical::after {
  top: 0;
  left: 0;
  width: 100%;
  height: var(--resizer-size);
}

.resizer:hover,
.resizer.active {
  background: rgba(0, 0, 0, 0.06);
}

.resizer:hover::after,
.resizer.active::after {
  background: var(--editor-text-dim);
}
.horizontal{
  cursor: w-resize;
}
.vertical{
  cursor: s-resize;
}

</style>
