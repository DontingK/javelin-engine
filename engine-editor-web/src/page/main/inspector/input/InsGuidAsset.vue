<template>
  <div
    class="ins-guid-asset"
    :class="{ none: isNone, memory: isMemory }"
    :title="tooltip"
  >
    {{ display }}
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * GUID 资产只读展示（Inspector 专用）。
 *
 * <p>三态 wire 契约（Java 侧 InspectorValueType.GUID_ASSET）：modelValue 为
 * null = 字段无资产（"None"）；空串 = 内存创建（guid 空，禁用态、不可回写）；
 * 非空 = guid 原串（显示 assetName，缺省兜底 guid）。v0.1 只读——赋值通道待
 * 项目面板拖拽资产落地（Inspector_Design 遗留），故无 update 事件。</p>
 */
interface Props {
  /** 当前值：null=None、空串=内存创建、非空=guid。 */
  modelValue: string | null
  /** 资产显示名（Java 侧 guid 反查源路径尾名；内存创建/反查失败为 null）。 */
  assetName?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  assetName: null,
})

/** null 与空串语义不同（None vs 内存创建），不可经 ?? 折叠。 */
const isNone = computed<boolean>(() => props.modelValue == null)
const isMemory = computed<boolean>(() => props.modelValue === '')

const display = computed<string>(() => {
  if (isNone.value) {
    return 'None'
  }
  if (isMemory.value) {
    return '（内存创建）'
  }
  // 此分支 modelValue 必为非空 guid；尾置 ?? '' 仅为满足 TS 收窄（不可达）
  return props.assetName ?? props.modelValue ?? ''
})

/** 悬停全文：有 guid 时带 guid 原串，便于排查反查失败/同名资产 */
const tooltip = computed<string>(() => {
  if (isNone.value || isMemory.value) {
    return display.value
  }
  return display.value + '\n' + props.modelValue
})
</script>

<style scoped>
/* 只读展示框：视输入控件同尺寸口径（padding 2px 守间距规范），无交互态 */
.ins-guid-asset {
  width: 100%;
  padding: 2px;
  font-size: 13px;
  color: var(--editor-text);
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ins-guid-asset.none,
.ins-guid-asset.memory {
  color: var(--editor-text-dim);
}

.ins-guid-asset.memory {
  font-style: italic;
  opacity: 0.75;
}
</style>
