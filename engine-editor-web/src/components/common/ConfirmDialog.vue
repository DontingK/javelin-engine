<template>
  <Teleport to="body">
    <Transition name="confirm-fade">
      <div v-if="visible" class="confirm-overlay">
        <div class="confirm-dialog" :class="`confirm-${options.type}`">
          <div class="confirm-header">
            <img :src="iconSrc" class="confirm-icon" :alt="options.type" />
            <span class="confirm-title">{{ options.title }}</span>
          </div>
          <div class="confirm-body">{{ options.message }}</div>
          <div class="confirm-footer">
            <button class="confirm-btn confirm-btn-cancel" @click="handleCancel">
              {{ options.cancelText }}
            </button>
            <button class="confirm-btn confirm-btn-ok" @click="handleOk">
              {{ options.okText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useConfirm } from '../../composables/useConfirm.ts'
import errorIcon from '../../assets/icon/errorDialog.svg'
import infoIcon from '../../assets/icon/informationDialog.svg'
import warningIcon from '../../assets/icon/warningDialog.svg'

const { visible, options, handleOk, handleCancel } = useConfirm()

/**
 * 根据弹窗类型返回对应的 icon 路径。
 */
const iconSrc = computed(() => {
  switch (options.value.type) {
    case 'error':
      return errorIcon
    case 'warning':
      return warningIcon
    case 'info':
    default:
      return infoIcon
  }
})
</script>

<style scoped>
.confirm-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.4);
  z-index: 9999;
}

.confirm-dialog {
  min-width: 320px;
  max-width: 480px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 6px;
  overflow: hidden;
}

.confirm-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 16px 8px;
}

.confirm-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.confirm-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--editor-text-heading);
}

.confirm-body {
  padding: 8px 16px 20px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--editor-text);
  white-space: pre-wrap;
}

.confirm-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 16px;
  background: var(--editor-surface-light);
  border-top: 1px solid var(--editor-border);
}

.confirm-btn {
  padding: 6px 14px;
  border-radius: 4px;
  font-size: 13px;
  transition: background 0.15s, border-color 0.15s;
}

.confirm-btn-cancel {
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  color: var(--editor-text);
}

.confirm-btn-cancel:hover {
  background: var(--editor-surface-light);
}

.confirm-btn-ok {
  background: var(--editor-accent);
  border: 1px solid var(--editor-accent);
  color: #ffffff;
}

.confirm-btn-ok:hover {
  background: var(--editor-accent-hover);
  border-color: var(--editor-accent-hover);
}

.confirm-error .confirm-btn-ok {
  background: #dc3545;
  border-color: #dc3545;
}

.confirm-error .confirm-btn-ok:hover {
  background: #bb2d3b;
  border-color: #bb2d3b;
}

.confirm-warning .confirm-btn-ok {
  background: #f59e0b;
  border-color: #f59e0b;
}

.confirm-warning .confirm-btn-ok:hover {
  background: #d97706;
  border-color: #d97706;
}

.confirm-fade-enter-active,
.confirm-fade-leave-active {
  transition: opacity 0.2s ease;
}

.confirm-fade-enter-from,
.confirm-fade-leave-to {
  opacity: 0;
}
</style>
