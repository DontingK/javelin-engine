<template>
  <button
    class="je-tool-button"
    :class="{ 'is-active': active }"
    :disabled="disabled"
    :title="title"
    type="button"
  >
    <slot />
  </button>
</template>

<script setup lang="ts">
/**
 * 工具栏按钮（icon / 文本通用，紧凑方寸）。
 *
 * <p>场景视图工具栏（Tools 组 + 局部/世界坐标切换）用。active 走 accent 色板
 * （反色图标/文字）、disabled 置灰（未实现能力的占位）；点击事件走原生
 * attribute 透传给父级驱动（桥调用与本地态归父级）。</p>
 */
interface Props {
  /** 是否激活（当前选中工具/空间） */
  active?: boolean
  /** 是否禁用（未实现能力占位置灰） */
  disabled?: boolean
  /** 悬停提示（title） */
  title?: string
}

withDefaults(defineProps<Props>(), {
  active: false,
  disabled: false,
  title: ''
})
</script>

<style scoped>
.je-tool-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 2px;
  border: none;
  background: transparent;
  color: var(--editor-text);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  user-select: none;
}

.je-tool-button:hover:not(:disabled) {
  background: var(--editor-surface-light);
}

.je-tool-button.is-active {
  background: var(--editor-accent);
  color: var(--editor-surface);
}

.je-tool-button.is-active:hover:not(:disabled) {
  background: var(--editor-accent-hover);
}

.je-tool-button:disabled {
  color: var(--editor-text-dim);
  opacity: 0.5;
  cursor: default;
}
</style>
