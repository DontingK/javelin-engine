<template>
  <button
    class="je-button"
    :class="[
      `je-button-${type}`,
      {
        'je-button-loading': loading,
        'je-button-disabled': disabled || loading
      }
    ]"
    :disabled="disabled || loading"
    @click="handleClick"
  >
    <span v-if="loading" class="je-button-spinner"></span>
    <span class="je-button-content">
      <slot />
    </span>
  </button>
</template>

<script setup lang="ts">
/**
 * Javelin 按钮组件。
 *
 * <p>支持主要、次要、危险三种类型，以及 loading 状态。</p>
 */
interface Props {
  /** 按钮类型 */
  type?: 'primary' | 'secondary' | 'danger'
  /** 是否处于加载状态 */
  loading?: boolean
  /** 是否禁用 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  type: 'primary',
  loading: false,
  disabled: false
})

const emit = defineEmits<{
  /** 点击事件 */
  click: [event: MouseEvent]
}>()

/**
 * 处理点击事件。
 *
 * @param event 鼠标事件
 */
function handleClick(event: MouseEvent): void {
  if (props.loading || props.disabled) {
    return
  }
  emit('click', event)
}
</script>

<style scoped>
.je-button {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, opacity 0.15s;
  border: 1px solid transparent;
}

.je-button-primary {
  background: var(--editor-accent);
  border-color: var(--editor-accent);
  color: #ffffff;
}

.je-button-primary:hover:not(:disabled) {
  background: var(--editor-accent-hover);
  border-color: var(--editor-accent-hover);
}

.je-button-secondary {
  background: var(--editor-surface);
  border-color: var(--editor-border);
  color: var(--editor-text);
}

.je-button-secondary:hover:not(:disabled) {
  background: var(--editor-surface-light);
}

.je-button-danger {
  background: #dc3545;
  border-color: #dc3545;
  color: #ffffff;
}

.je-button-danger:hover:not(:disabled) {
  background: #bb2d3b;
  border-color: #bb2d3b;
}

.je-button-loading,
.je-button-disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.je-button-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: je-button-spin 0.8s linear infinite;
  flex-shrink: 0;
}

.je-button-content {
  display: inline-flex;
  align-items: center;
}

@keyframes je-button-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
