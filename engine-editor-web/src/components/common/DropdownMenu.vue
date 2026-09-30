<template>
  <div
      ref="menuEl"
      class="dropdown-menu"
      :style="{ left: left + 'px', top: top + 'px' }"
  >
    <template v-for="(item, index) in items" :key="item.id ?? 'sep-' + index">
      <div v-if="item.separator" class="dropdown-menu__separator"></div>
      <div
          v-else
          class="dropdown-menu__item"
          :class="{ 'dropdown-menu__item--disabled': item.enabled === false }"
          @click="onItemClick(item)"
      >
        <span class="dropdown-menu__check">{{ item.checkable && item.checked ? '✓' : '' }}</span>
        <span class="dropdown-menu__label">{{ item.label }}</span>
        <span v-if="item.shortcut" class="dropdown-menu__shortcut">{{ item.shortcut }}</span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { MenuItemVo } from '../../service/menuService.ts'

/**
 * 通用下拉/右键菜单组件（面板右键菜单用，设计档 Editor_Menu_Design §5.2——
 * 面板区域不进视口矩形走 Web 自渲染，本组件为场景树任务首消费）。
 *
 * <p>菜单浮层须留在 boundary 矩形内（面板 DOM）：挖洞式视口结构下，Web 浮层
 * 延伸进视口矩形会被上层 AWT Canvas 盖住（设计档 §1.1 遮挡判定），挂载后测量
 * 自身尺寸并夹紧在边界内（右/下溢出向左/上翻转）。点击组件外/Esc 关闭。</p>
 */
interface Props {
  /** 菜单项（/menu/list 域拉取结果，含即时求值的 enabled/checked） */
  items: MenuItemVo[]
  /** 弹出位置 x（clientX，web 坐标） */
  x: number
  /** 弹出位置 y（clientY，web 坐标） */
  y: number
  /** 夹紧边界元素（面板 DOM；缺省用浏览器视口） */
  boundary?: HTMLElement | null
}

const props = withDefaults(defineProps<Props>(), {
  boundary: null
})

const emit = defineEmits<{
  /** 点击菜单项（enabled=false 置灰项不触发） */
  (e: 'select', item: MenuItemVo): void
  /** 请求关闭（点击外部/Esc/点击项后） */
  (e: 'close'): void
}>()

const menuEl = ref<HTMLElement | null>(null)
const left = ref(props.x)
const top = ref(props.y)

onMounted(() => {
  clampIntoBoundary()
  window.addEventListener('mousedown', onGlobalMouseDown, true)
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('mousedown', onGlobalMouseDown, true)
  window.removeEventListener('keydown', onKeyDown)
})

/**
 * 夹紧在边界矩形内（挂载后 DOM 已渲染可测量；溢出右/下缘时向左/上翻转，
 * 翻转不越左/上缘——菜单极宽时宁可不完整也不出面板）。
 */
function clampIntoBoundary(): void {
  const el = menuEl.value
  if (el == null) {
    return
  }
  const rect = el.getBoundingClientRect()
  const bounds = props.boundary?.getBoundingClientRect() ?? null
  const maxX = (bounds ? bounds.right : window.innerWidth) - 4
  const maxY = (bounds ? bounds.bottom : window.innerHeight) - 4
  if (props.x + rect.width > maxX) {
    left.value = Math.max(props.x - rect.width - 8, bounds ? bounds.left + 4 : 4)
  }
  if (props.y + rect.height > maxY) {
    top.value = Math.max(props.y - rect.height - 8, bounds ? bounds.top + 4 : 4)
  }
}

/**
 * 点击组件外关闭（capture 阶段监听，先于面板事件）。
 *
 * @param event 鼠标事件
 */
function onGlobalMouseDown(event: MouseEvent): void {
  const el = menuEl.value
  if (el != null && event.target instanceof Node && !el.contains(event.target)) {
    emit('close')
  }
}

/**
 * Esc 关闭。
 *
 * @param event 键盘事件
 */
function onKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
  }
}

/**
 * 菜单项点击：置灰项不响应，其余通知父级并关闭。
 *
 * @param item 菜单项
 */
function onItemClick(item: MenuItemVo): void {
  if (item.enabled === false) {
    return
  }
  emit('select', item)
  emit('close')
}
</script>

<style scoped>
.dropdown-menu {
  position: fixed;
  z-index: 1000;
  min-width: 160px;
  background: var(--editor-surface);
  border: 1px solid var(--editor-border);
  border-radius: 4px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  padding: 4px 0;
  font-size: 12px;
  color: var(--editor-text);
  user-select: none;
}

.dropdown-menu__separator {
  height: 1px;
  margin: 4px 8px;
  background: var(--editor-border);
}

.dropdown-menu__item {
  display: flex;
  align-items: center;
  padding: 4px 10px;
  cursor: default;
}

.dropdown-menu__item:hover {
  background: var(--editor-surface-light);
}

.dropdown-menu__item--disabled {
  color: var(--editor-text-dim);
}

.dropdown-menu__item--disabled:hover {
  background: none;
}

.dropdown-menu__check {
  display: inline-flex;
  width: 16px;
  flex-shrink: 0;
  justify-content: center;
}

.dropdown-menu__label {
  flex: 0 0 auto;
  white-space: nowrap;
}

.dropdown-menu__shortcut {
  margin-left: auto;
  padding-left: 24px;
  color: var(--editor-text-dim);
}
</style>
