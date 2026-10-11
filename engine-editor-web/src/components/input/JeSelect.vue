<template>
  <div ref="containerRef" class="je-select-container">
    <!-- 触发区域 -->
    <div
      class="je-select-trigger"
      :class="{ open: isOpen, disabled }"
      @click="toggleDropdown"
    >
      <span class="trigger-text">{{ displayText }}</span>
      <span class="trigger-arrow">▾</span>
    </div>

    <!-- 下拉面板 -->
    <Transition name="dropdown">
      <div
        v-if="isOpen"
        class="je-select-dropdown"
        :class="{ 'drop-up': dropUp }"
      >
        <div v-if="multiple" class="dropdown-header">
          <button class="header-btn" @click="selectAll">全选</button>
          <button class="header-btn" @click="deselectAll">清空</button>
        </div>
        <div class="dropdown-list">
          <div
            v-for="option in options"
            :key="option.value"
            class="dropdown-item"
            :class="{ selected: isSelected(option.value) }"
            @click="onSelect(option.value)"
          >
            <JeCheckBox
              v-if="multiple"
              :model-value="isSelected(option.value)"
              class="item-checkbox"
            />
            <span class="item-label">{{ option.label }}</span>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import JeCheckBox from './JeCheckBox.vue'

/**
 * 自定义下拉选择组件。
 *
 * <p>支持单选和多选模式，自动判断向上或向下展开。</p>
 */

/** 选择项数据。 */
export interface SelectOption {
  /** 选项值。 */
  value: string
  /** 选项显示文本。 */
  label: string
}

interface Props {
  /** 当前选中值，多选时为逗号分隔的字符串。 */
  modelValue: string
  /** 选项列表。 */
  options: SelectOption[]
  /** 占位文本。 */
  placeholder?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
  /** 是否多选，默认为 false。 */
  multiple?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '请选择',
  disabled: false,
  multiple: false,
})

const emit = defineEmits<{
  /** 选中值变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

/** 下拉面板是否展开。 */
const isOpen = ref(false)

/** 是否向上展开。 */
const dropUp = ref(false)

/** 容器元素引用。 */
const containerRef = ref<HTMLElement | null>(null)

/**
 * 解析当前选中值为 Set。
 */
const selectedValues = computed(() => {
  if (props.multiple) {
    return new Set(props.modelValue.split(',').filter(Boolean))
  }
  return new Set([props.modelValue])
})

/**
 * 显示文本。
 */
const displayText = computed(() => {
  if (props.multiple) {
    const count = selectedValues.value.size
    if (count === 0) return props.placeholder
    if (count <= 2) {
      return props.options
        .filter(o => selectedValues.value.has(o.value))
        .map(o => o.label)
        .join(', ')
    }
    return `已选择 ${count} 项`
  }

  const selected = props.options.find(o => o.value === props.modelValue)
  return selected ? selected.label : props.placeholder
})

/**
 * 计算下拉方向。
 */
function calcDropDirection(): void {
  if (!containerRef.value) return

  const rect = containerRef.value.getBoundingClientRect()
  const viewportHeight = window.innerHeight
  const dropdownHeight = 240 + (props.multiple ? 36 : 0) // 下拉最大高度 + header

  // 下方空间不足时向上展开
  dropUp.value = rect.bottom + dropdownHeight > viewportHeight && rect.top > dropdownHeight
}

/**
 * 判断选项是否选中。
 *
 * @param value 选项值
 * @returns 是否选中
 */
function isSelected(value: string): boolean {
  return selectedValues.value.has(value)
}

/**
 * 切换下拉面板。
 */
function toggleDropdown(): void {
  if (!props.disabled) {
    isOpen.value = !isOpen.value
    if (isOpen.value) {
      nextTick(calcDropDirection)
    }
  }
}

/**
 * 关闭下拉面板。
 */
function closeDropdown(): void {
  isOpen.value = false
}

/**
 * 选择选项。
 *
 * @param value 选项值
 */
function onSelect(value: string): void {
  if (props.multiple) {
    const set = new Set(selectedValues.value)
    if (set.has(value)) {
      set.delete(value)
    } else {
      set.add(value)
    }
    emit('update:modelValue', Array.from(set).join(','))
  } else {
    emit('update:modelValue', value)
    closeDropdown()
  }
}

/**
 * 全选。
 */
function selectAll(): void {
  const allValues = props.options.map(o => o.value)
  emit('update:modelValue', allValues.join(','))
}

/**
 * 清空。
 */
function deselectAll(): void {
  emit('update:modelValue', '')
}

/**
 * 点击外部关闭。
 *
 * @param event 鼠标事件
 */
function onClickOutside(event: MouseEvent): void {
  if (containerRef.value && !containerRef.value.contains(event.target as Node)) {
    closeDropdown()
  }
}

/**
 * 窗口大小变化时重新计算方向。
 */
function onResize(): void {
  if (isOpen.value) {
    calcDropDirection()
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onClickOutside)
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.je-select-container {
  position: relative;
  width: 100%;
}

/* 触发区域 */
.je-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 2px;
  font-size: 13px;
  color: var(--editor-text);
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  cursor: pointer;
  user-select: none;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.je-select-trigger:hover:not(.disabled) {
  background: var(--editor-surface);
}

.je-select-trigger.open {
  background: var(--editor-surface);
  border-color: var(--editor-accent);
}

.je-select-trigger.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.trigger-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trigger-arrow {
  margin-left: 2px;
  font-size: 10px;
  opacity: 0.6;
  transition: transform 0.2s ease;
}

.je-select-trigger.open .trigger-arrow {
  transform: rotate(180deg);
}

/* 下拉面板 - 默认向下 */
.je-select-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 100;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 4px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  overflow: hidden;
}

/* 向上展开 */
.je-select-dropdown.drop-up {
  top: auto;
  bottom: calc(100% + 4px);
}

/* 下拉面板头部 */
.dropdown-header {
  display: flex;
  gap: 8px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--editor-border);
}

.header-btn {
  padding: 2px 8px;
  font-size: 11px;
  color: var(--editor-text-secondary);
  background: transparent;
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.header-btn:hover {
  color: var(--editor-text);
  background: var(--editor-surface-light);
}

/* 下拉列表 */
.dropdown-list {
  max-height: 240px;
  overflow-y: auto;
  padding: 4px;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 3px;
  cursor: pointer;
  user-select: none;
  transition: background 0.1s ease;
}

.dropdown-item:hover {
  background: var(--editor-surface-light);
}

.dropdown-item.selected {
  color: var(--editor-accent);
}

.item-checkbox {
  flex-shrink: 0;
}

.item-label {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 过渡动画 */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.drop-up.dropdown-enter-from,
.drop-up.dropdown-leave-to {
  transform: translateY(4px);
}

/* 滚动条样式 */
.dropdown-list::-webkit-scrollbar {
  width: 6px;
}

.dropdown-list::-webkit-scrollbar-track {
  background: transparent;
}

.dropdown-list::-webkit-scrollbar-thumb {
  background: var(--editor-border);
  border-radius: 3px;
}

.dropdown-list::-webkit-scrollbar-thumb:hover {
  background: var(--editor-text-secondary);
}
</style>
