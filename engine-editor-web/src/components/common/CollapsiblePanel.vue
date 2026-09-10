<template>
  <div class="collapsible-panel" :class="{ expanded: isExpanded }">
    <button
      class="collapsible-header"
      type="button"
      :aria-expanded="isExpanded"
      @click="toggle"
    >
      <span class="collapsible-arrow" aria-hidden="true">▶</span>
      <span v-if="icon" class="collapsible-icon">
        <img v-if="isIconPath" :src="icon" class="collapsible-icon-img" alt="" />
        <template v-else>{{ icon }}</template>
      </span>
      <span class="collapsible-title">{{ title }}</span>
    </button>
    <div v-show="isExpanded" class="collapsible-content">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'

/**
 * 折叠面板组件。
 *
 * <p>提供可展开/收起的面板容器，常用于 Inspector 中按组件折叠属性区域。</p>
 */

interface Props {
  /** 面板标题。 */
  title: string
  /** 标题前的图标，支持 emoji 文本或图片路径（以 / 开头）。 */
  icon?: string
  /** 是否默认展开，默认为 true。 */
  defaultExpanded?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  defaultExpanded: true,
})

const emit = defineEmits<{
  /** 展开状态变化时触发。 */
  (e: 'toggle', expanded: boolean): void
}>()

const isExpanded = ref(props.defaultExpanded)

/**
 * 判断 icon 是否为图片路径。
 */
const isIconPath = computed(() => {
  return props.icon && (props.icon.startsWith('/') || props.icon.startsWith('./'))
})

watch(
  () => props.defaultExpanded,
  (value) => {
    isExpanded.value = value
  },
)

/**
 * 切换展开/收起状态。
 */
function toggle(): void {
  isExpanded.value = !isExpanded.value
  emit('toggle', isExpanded.value)
}
</script>

<style scoped>
.collapsible-panel {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  overflow: hidden;
}

.collapsible-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--editor-text-heading);
  background: var(--editor-surface-light);
  border: none;
  cursor: pointer;
  user-select: none;
  text-align: left;
}

.collapsible-header:hover {
  background: var(--editor-border);
}

.collapsible-arrow {
  display: inline-block;
  font-size: 10px;
  color: var(--editor-text-dim);
  transition: transform 0.15s ease;
}

.collapsible-panel.expanded .collapsible-arrow {
  transform: rotate(90deg);
}

.collapsible-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  font-size: 11px;
  color: var(--editor-text-dim);
}

.collapsible-icon-img {
  width: 14px;
  height: 14px;
  object-fit: contain;
}

.collapsible-title {
  flex: 1;
}

.collapsible-content {
  padding: 8px;
}
</style>
