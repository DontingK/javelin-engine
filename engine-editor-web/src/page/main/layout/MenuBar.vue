<template>
  <nav class="menu-bar" @mouseleave="closeMenu">
    <div
      v-for="(menu, index) in menus"
      :key="menu.label"
      class="menu-root"
      :class="{ active: activeIndex === index }"
      @mouseenter="openMenu(index)"
      @click="openMenu(index)"
    >
      <button class="menu-label">{{ menu.label }}</button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

/**
 * 编辑器顶部菜单栏。
 *
 * <p>TODO: 后端接口对接，从 Java 后端获取菜单列表并弹出 Swing 子菜单。</p>
 */

interface MenuDefinition {
  label: string
}

const menus = ref<MenuDefinition[]>([])
const activeIndex = ref<number | null>(null)

/**
 * 加载菜单列表。
 */
function loadMenus(): void {
  // TODO: 后端接口对接
  console.log('[MenuBar] loadMenus - 待对接后端接口')
  // 临时：静态菜单数据
  menus.value = [
    { label: '文件' },
    { label: '编辑' },
    { label: '视图' },
    { label: '构建' },
    { label: '帮助' }
  ]
}

function openMenu(index: number): void {
  activeIndex.value = index
  const menu = menus.value[index]
  if (menu) {
    // TODO: 后端接口对接（弹出 Swing 菜单）
    console.log('[MenuBar] openMenu - 待对接后端接口, menu:', menu.label)
  }
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
