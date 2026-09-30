<template>
  <nav class="menu-bar" @mouseleave="closeMenu">
    <div
        v-for="(menu, index) in menus"
        :key="menu.id"
        class="menu-root"
        :class="{ active: activeIndex === index }"
        @mouseenter="openMenu(index)"
        @click="onMenuClick(index, $event)"
    >
      <button class="menu-label">{{ menu.label }}</button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listMenus, openMenu as openMenuPopup, type MenuItemVo } from '../../../service/menuService.ts'

/**
 * 编辑器顶部菜单栏。
 *
 * <p>标签由 Java 侧 MenuRegistry 经 /menu/list 拉取（声明式单源）；点击标签经
 * /menu/open 传 web 坐标，Java 侧弹 Swing JPopupMenu——浮于视口 Canvas 之上
 * （挖洞遮挡约束，见设计档 Editor_Menu_Design §5.1），Web 下拉不可用。</p>
 */

/** 引擎侧菜单装配可能晚于页面挂载（启动竞态），空列表节流重试 */
const MAX_RETRY = 20
const RETRY_DELAY_MS = 500

const menus = ref<MenuItemVo[]>([])
const activeIndex = ref<number | null>(null)
let retryCount = 0

/**
 * 拉取顶栏菜单列表。
 */
async function loadMenus(): Promise<void> {
  try {
    const list = await listMenus()
    if (list.length > 0) {
      menus.value = list
    } else if (retryCount++ < MAX_RETRY) {
      setTimeout(loadMenus, RETRY_DELAY_MS)
    }
  } catch (e) {
    console.warn('[MenuBar] 拉取菜单失败', e)
  }
}

/**
 * 悬停高亮。
 *
 * @param index 菜单索引
 */
function openMenu(index: number): void {
  activeIndex.value = index
}

/**
 * 点击标签：转发 /menu/open（x = 标签左缘、y = 顶栏下缘，web 坐标），
 * Java 侧弹 Swing JPopupMenu。
 *
 * @param index 菜单索引
 * @param event 鼠标事件
 */
function onMenuClick(index: number, event: MouseEvent): void {
  activeIndex.value = index
  const menu = menus.value[index]
  if (menu == null) {
    return
  }
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  openMenuPopup(menu.id, rect.left, rect.bottom).catch(e => console.warn('[MenuBar] 菜单弹出失败', e))
}

function closeMenu(): void {
  activeIndex.value = null
}

onMounted(() => {
  loadMenus()
})
</script>

<style scoped>
.menu-bar {
  display: flex;
  align-items: center;
  height: var(--menu-height);
  background: var(--editor-surface);
  border-bottom: 1px solid var(--editor-border);
  user-select: none;
  padding: 0 6px;
}

.menu-root {
  position: relative;
  display: flex;
  align-items: center;
}

.menu-label {
  padding: 0 10px;
  height: var(--menu-height);
  line-height: var(--menu-height);
  border-radius: 4px;
  transition: background 0.15s, color 0.15s;
}

.menu-root:hover .menu-label {
  background: rgba(0, 0, 0, 0.06);
}

.menu-root.active .menu-label {
  background: #ffffff;
  color: var(--editor-text-heading);
  box-shadow: inset 0 0 0 1px var(--editor-border);
}
</style>
