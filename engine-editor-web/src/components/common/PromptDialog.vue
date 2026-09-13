<template>
  <Teleport to="body">
    <Transition name="prompt-fade">
      <div v-if="visible" class="prompt-overlay" @click="handleCancel">
        <div class="prompt-dialog" @click.stop>
          <div class="prompt-header">
            <span class="prompt-title">{{ options.title }}</span>
          </div>
          <div class="prompt-body">
            <div v-if="options.message" class="prompt-message">{{ options.message }}</div>
            <input
              ref="inputRef"
              v-model="inputValue"
              class="prompt-input"
              type="text"
              @keydown.enter="handleOk"
              @keydown.esc="handleCancel"
            />
          </div>
          <div class="prompt-footer">
            <button class="prompt-btn prompt-btn-cancel" @click="handleCancel">
              {{ options.cancelText }}
            </button>
            <button class="prompt-btn prompt-btn-ok" @click="handleOk">
              {{ options.okText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { usePrompt } from '../../composables/usePrompt.ts'

const { visible, inputValue, options, handleOk, handleCancel } = usePrompt()
const inputRef = ref<HTMLInputElement | null>(null)

watch(visible, (isVisible) => {
  if (isVisible) {
    nextTick(() => {
      inputRef.value?.focus()
      inputRef.value?.select()
    })
  }
})
</script>

<style scoped>
.prompt-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.4);
  z-index: 9999;
}

.prompt-dialog {
  min-width: 320px;
  max-width: 480px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 6px;
  overflow: hidden;
}

.prompt-header {
  padding: 16px 16px 8px;
}

.prompt-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--editor-text-heading);
}

.prompt-body {
  padding: 8px 16px 20px;
}

.prompt-message {
  margin-bottom: 12px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--editor-text);
  white-space: pre-wrap;
}

.prompt-input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  font-size: 14px;
  color: var(--editor-text);
  background: var(--editor-bg);
  border: 1px solid var(--editor-border);
  border-radius: 4px;
  outline: none;
}

.prompt-input:focus {
  border-color: var(--editor-accent);
}

.prompt-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 16px;
  background: var(--editor-surface-light);
  border-top: 1px solid var(--editor-border);
}

.prompt-btn {
  padding: 6px 14px;
  border-radius: 4px;
  font-size: 13px;
  transition: background 0.15s, border-color 0.15s;
}

.prompt-btn-cancel {
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  color: var(--editor-text);
}

.prompt-btn-cancel:hover {
  background: var(--editor-surface-light);
}

.prompt-btn-ok {
  background: var(--editor-accent);
  border: 1px solid var(--editor-accent);
  color: #ffffff;
}

.prompt-btn-ok:hover {
  background: var(--editor-accent-hover);
  border-color: var(--editor-accent-hover);
}

.prompt-fade-enter-active,
.prompt-fade-leave-active {
  transition: opacity 0.2s ease;
}

.prompt-fade-enter-from,
.prompt-fade-leave-to {
  opacity: 0;
}
</style>
