<template>
  <div class="je-color">
    <div class="color-preview" :style="{ backgroundColor: cssColor }" @click="openPicker" />
    <JeInput
      :model-value="displayValue"
      class="color-input"
      @update:model-value="onInput"
    />
    <input
      ref="pickerRef"
      type="color"
      class="native-color-picker"
      :value="hexColor"
      @input="onPickerChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import JeInput from './JeInput.vue'

/**
 * 通用颜色输入组件。
 *
 * <p>支持 v-model 绑定颜色字符串，提供颜色预览块、文本输入框和原生颜色选择器。
 * 通过 {@code format} prop 控制输出格式：</p>
 * <ul>
 *   <li>{@code 'hex'} — 十六进制，如 {@code #ff0000} 或 {@code #ff0000ff}（默认）</li>
 *   <li>{@code 'rgb'} — RGB 格式，如 {@code rgb(255,0,0)}</li>
 *   <li>{@code 'rgba'} — RGBA 格式，如 {@code rgba(255,0,0,1)}</li>
 * </ul>
 */

interface Props {
  /** 当前颜色值字符串。 */
  modelValue: string
  /**
   * 输出格式。
   *
   * <ul>
   *   <li>{@code 'hex'} — 十六进制（默认）</li>
   *   <li>{@code 'rgb'} — RGB</li>
   *   <li>{@code 'rgba'} — RGBA</li>
   * </ul>
   */
  format?: 'hex' | 'rgb' | 'rgba'
}

const props = withDefaults(defineProps<Props>(), {
  format: 'hex',
})

const emit = defineEmits<{
  /** 颜色值变化时触发。 */
  (e: 'update:modelValue', value: string): void
}>()

const pickerRef = ref<HTMLInputElement | null>(null)

/**
 * 解析输入字符串为 RGBA 分量（0-255）。
 */
const parsedRgba = computed(() => {
  const s = (props.modelValue || '').trim()
  if (!s) {
    return { r: 0, g: 0, b: 0, a: 255 }
  }

  // hex: #rrggbb 或 #rrggbbaa
  if (s.startsWith('#')) {
    const hex = s.slice(1)
    if (hex.length === 6 || hex.length === 8) {
      return {
        r: parseInt(hex.slice(0, 2), 16),
        g: parseInt(hex.slice(2, 4), 16),
        b: parseInt(hex.slice(4, 6), 16),
        a: hex.length === 8 ? parseInt(hex.slice(6, 8), 16) : 255,
      }
    }
  }

  // rgb(r,g,b)
  const rgbMatch = s.match(/rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/)
  if (rgbMatch) {
    return {
      r: parseInt(rgbMatch[1], 10),
      g: parseInt(rgbMatch[2], 10),
      b: parseInt(rgbMatch[3], 10),
      a: 255,
    }
  }

  // rgba(r,g,b,a)
  const rgbaMatch = s.match(/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/)
  if (rgbaMatch) {
    return {
      r: parseInt(rgbaMatch[1], 10),
      g: parseInt(rgbaMatch[2], 10),
      b: parseInt(rgbaMatch[3], 10),
      a: Math.round(parseFloat(rgbaMatch[4]) * 255),
    }
  }

  // r,g,b,a (0-1 float) — 兼容后端 Color.toString 格式
  const parts = s.split(',').map(Number.parseFloat)
  if (parts.length >= 3 && parts.every(Number.isFinite)) {
    return {
      r: Math.round(clamp01(parts[0]) * 255),
      g: Math.round(clamp01(parts[1]) * 255),
      b: Math.round(clamp01(parts[2]) * 255),
      a: parts.length >= 4 && Number.isFinite(parts[3]) ? Math.round(clamp01(parts[3]) * 255) : 255,
    }
  }

  return { r: 0, g: 0, b: 0, a: 255 }
})

/**
 * 用于 CSS backgroundColor 的 rgba 字符串。
 */
const cssColor = computed(() => {
  const c = parsedRgba.value
  return `rgba(${c.r}, ${c.g}, ${c.b}, ${c.a / 255})`
})

/**
 * 用于原生颜色选择器的 hex 值（不含 alpha）。
 */
const hexColor = computed(() => {
  const c = parsedRgba.value
  const r = c.r.toString(16).padStart(2, '0')
  const g = c.g.toString(16).padStart(2, '0')
  const b = c.b.toString(16).padStart(2, '0')
  return `#${r}${g}${b}`
})

/**
 * 显示用的文本值。
 */
const displayValue = computed(() => {
  const c = parsedRgba.value
  return formatColor(c.r, c.g, c.b, c.a, props.format)
})

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v))
}

function formatColor(r: number, g: number, b: number, a: number, format: string): string {
  const rr = r.toString(16).padStart(2, '0')
  const gg = g.toString(16).padStart(2, '0')
  const bb = b.toString(16).padStart(2, '0')
  const aa = a.toString(16).padStart(2, '0')

  switch (format) {
    case 'rgb':
      return `rgb(${r},${g},${b})`
    case 'rgba':
      return `rgba(${r},${g},${b},${(a / 255).toFixed(3)})`
    case 'hex':
    default:
      return a < 255 ? `#${rr}${gg}${bb}${aa}` : `#${rr}${gg}${bb}`
  }
}

/**
 * 打开原生颜色选择器。
 */
function openPicker(): void {
  pickerRef.value?.click()
}

/**
 * 处理原生颜色选择器变更。
 */
function onPickerChange(event: Event): void {
  const target = event.target as HTMLInputElement
  const hex = target.value
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  const a = parsedRgba.value.a
  emit('update:modelValue', formatColor(r, g, b, a, props.format))
}

/**
 * 处理文本输入变更。
 */
function onInput(value: string): void {
  const s = value.trim()
  if (!s) {
    return
  }

  // 尝试解析输入并重新格式化
  const c = parseColorString(s)
  if (c) {
    emit('update:modelValue', formatColor(c.r, c.g, c.b, c.a, props.format))
  }
}

function parseColorString(s: string): { r: number; g: number; b: number; a: number } | null {
  if (s.startsWith('#')) {
    const hex = s.slice(1)
    if (hex.length === 6 || hex.length === 8) {
      return {
        r: parseInt(hex.slice(0, 2), 16),
        g: parseInt(hex.slice(2, 4), 16),
        b: parseInt(hex.slice(4, 6), 16),
        a: hex.length === 8 ? parseInt(hex.slice(6, 8), 16) : 255,
      }
    }
  }

  const rgbMatch = s.match(/rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/)
  if (rgbMatch) {
    return {
      r: parseInt(rgbMatch[1], 10),
      g: parseInt(rgbMatch[2], 10),
      b: parseInt(rgbMatch[3], 10),
      a: 255,
    }
  }

  const rgbaMatch = s.match(/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/)
  if (rgbaMatch) {
    return {
      r: parseInt(rgbaMatch[1], 10),
      g: parseInt(rgbaMatch[2], 10),
      b: parseInt(rgbaMatch[3], 10),
      a: Math.round(parseFloat(rgbaMatch[4]) * 255),
    }
  }

  const parts = s.split(',').map(Number.parseFloat)
  if (parts.length >= 3 && parts.every(Number.isFinite)) {
    return {
      r: Math.round(clamp01(parts[0]) * 255),
      g: Math.round(clamp01(parts[1]) * 255),
      b: Math.round(clamp01(parts[2]) * 255),
      a: parts.length >= 4 && Number.isFinite(parts[3]) ? Math.round(clamp01(parts[3]) * 255) : 255,
    }
  }

  return null
}
</script>

<style scoped>
.je-color {
  display: flex;
  align-items: center;
  gap: 2px;
  width: 100%;
}

.color-preview {
  width: 20px;
  height: 20px;
  border-radius: 3px;
  border: 1px solid var(--editor-border);
  cursor: pointer;
  flex-shrink: 0;
}

.color-input {
  flex: 1;
  min-width: 0;
}

.color-input :deep(.je-input) {
  font-size: 12px;
  padding: 2px;
}

.native-color-picker {
  position: absolute;
  opacity: 0;
  pointer-events: none;
  width: 0;
  height: 0;
}
</style>
