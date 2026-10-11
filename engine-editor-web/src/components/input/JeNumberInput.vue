<template>
  <input
    :value="displayValue"
    type="number"
    step="any"
    :min="min"
    :max="max"
    :placeholder="placeholder"
    :disabled="disabled"
    class="je-number-input"
    @input="onInput"
    @blur="onBlur"
    @wheel="onWheel"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 自定义数字输入框组件。
 *
 * <p>支持整数和浮点数，支持 v-model 绑定，滚轮按 step 增减数值。
 * 浮点显示与输入统一限制最多 precision 位小数（默认 4）。</p>
 */

interface Props {
  /** 当前数值。 */
  modelValue: number
  /** 是否只允许整数，默认为 false。 */
  integer?: boolean
  /** 最小值。 */
  min?: number
  /** 最大值。 */
  max?: number
  /** 步长，默认为 1。 */
  step?: number
  /** 小数最大位数，默认为 4（四舍五入，显示与输入同口径限制）。 */
  precision?: number
  /** 占位文本。 */
  placeholder?: string
  /** 是否禁用，默认为 false。 */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  integer: false,
  step: 1,
  precision: 4,
  placeholder: '',
  disabled: false,
})

const emit = defineEmits<{
  /** 数值变化时触发。 */
  (e: 'update:modelValue', value: number): void
}>()

const displayValue = computed(() => {
  return props.integer ? Math.round(props.modelValue) : limitPrecision(props.modelValue)
})

/**
 * 解析输入字符串为数字。
 *
 * @param value 输入字符串
 * @returns 解析后的数字
 */
function parseValue(value: string): number {
  const parsed = props.integer ? parseInt(value, 10) : parseFloat(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

/**
 * 限制数值的小数位数（四舍五入 + 去尾零）。
 *
 * @param value 数值
 * @returns 限制后的数值
 */
function limitPrecision(value: number): number {
  const digits = Math.max(0, Math.floor(props.precision))
  return Number(value.toFixed(digits))
}

/**
 * 处理 input 事件。
 *
 * @param event 输入事件
 */
function onInput(event: Event): void {
  const target = event.target as HTMLInputElement
  const parsed = parseValue(target.value)
  emit('update:modelValue', props.integer ? parsed : limitPrecision(parsed))
}

/**
 * 处理 blur 事件，格式化显示值。
 */
function onBlur(): void {
  let value = props.modelValue
  if (props.min !== undefined && value < props.min) {
    value = props.min
  }
  if (props.max !== undefined && value > props.max) {
    value = props.max
  }
  if (value !== props.modelValue) {
    emit('update:modelValue', value)
  }
}

/**
 * 处理滚轮事件，按 step 增减数值。
 *
 * @param event 滚轮事件
 */
function onWheel(event: WheelEvent): void {
  event.preventDefault()
  event.stopPropagation()

  const direction = event.deltaY < 0 ? 1 : -1
  let newValue = props.modelValue + props.step * direction

  // 取 step 精度和当前值精度的较大值，保留用户输入的小数，
  // 上限 precision（默认 4），避免浮点运算误差导致出现过多小数位
  const stepPrecision = getPrecision(props.step)
  const valuePrecision = getPrecision(props.modelValue)
  const precision = Math.min(Math.max(stepPrecision, valuePrecision), props.precision)

  if (precision > 0) {
    newValue = parseFloat(newValue.toFixed(precision))
  }

  if (props.integer) {
    newValue = Math.round(newValue)
  }

  if (props.min !== undefined && newValue < props.min) {
    newValue = props.min
  }
  if (props.max !== undefined && newValue > props.max) {
    newValue = props.max
  }

  emit('update:modelValue', newValue)
}

/**
 * 获取数值的小数位数。
 *
 * @param value 数值
 * @returns 小数位数
 */
function getPrecision(value: number): number {
  const str = String(value)
  const dotIndex = str.indexOf('.')
  if (dotIndex === -1) return 0
  return str.length - dotIndex - 1
}
</script>

<style scoped>
.je-number-input {
  width: 100%;
  padding: 2px;
  font-size: 13px;
  color: var(--editor-text);
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  outline: none;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.je-number-input:hover {
  background: var(--editor-surface);
}

.je-number-input:focus {
  background: var(--editor-surface);
  border-color: var(--editor-accent);
}

.je-number-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: var(--editor-surface-light);
}

.je-number-input::-webkit-inner-spin-button,
.je-number-input::-webkit-outer-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.je-number-input[type='number'] {
  -moz-appearance: textfield;
}
</style>
