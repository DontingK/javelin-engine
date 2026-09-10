<template>
  <footer class="status-bar">
    <span class="status-left" :class="{ connected: isConnected }">
      <span class="status-dot"></span>
      {{ leftText }}
    </span>
    <span class="status-right">{{ rightText }}</span>
  </footer>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import javaScriptBridge from '../../../bridge/javaScriptBridge.ts'

/**
 * 编辑器底部状态栏。
 *
 * <p>JCEF 桥接初始化完成后，左侧状态显示绿色圆点和绿色文字。</p>
 */

interface StatusBarProps {
  /** 左侧状态文本 */
  leftText?: string
  /** 右侧状态文本 */
  rightText?: string
}

withDefaults(defineProps<StatusBarProps>(), {
  leftText: '就绪',
  rightText: 'Javelin Engine 2 Web Editor'
})

const isConnected = ref(false)
let unsubscribe: (() => void) | null = null

onMounted(() => {
  unsubscribe = javaScriptBridge.onConnectionChange((connected: boolean) => {
    isConnected.value = connected
  })
})

onUnmounted(() => {
  unsubscribe?.()
})
</script>

<style scoped>
.status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: var(--status-height);
  padding: 0 10px;
  background: var(--editor-status-bg);
  border-top: 1px solid var(--editor-border);
  color: var(--editor-text-dim);
  font-size: 12px;
  user-select: none;
}

.status-left,
.status-right {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--editor-text-dim);
  transition: background 0.2s;
}

.status-left.connected {
  color: var(--editor-success, #4ade80);
}

.status-left.connected .status-dot {
  background: var(--editor-success, #4ade80);
}
</style>
