import { ref } from 'vue'

/**
 * 全局选中状态组合式函数。
 *
 * <p>维护当前选中的实体 ID，供层级面板、属性面板、Gizmo 等共享。</p>
 */

/** 当前选中的实体 ID，为 undefined 表示未选中。 */
const selectedEntityId = ref<number | undefined>(undefined)

/**
 * 设置当前选中的实体 ID。
 *
 * @param id 实体 ID，传 undefined 表示取消选中
 */
export function setSelectedEntityId(id: number | undefined): void {
  selectedEntityId.value = id
}

/**
 * 获取当前选中的实体 ID。
 *
 * @returns 实体 ID 或 undefined
 */
export function getSelectedEntityId(): number | undefined {
  return selectedEntityId.value
}

/**
 * 使用选中状态。
 *
 * @returns 选中实体 ID 的响应式引用
 */
export function useSelection() {
  return {
    selectedEntityId,
    setSelectedEntityId,
    getSelectedEntityId,
  }
}
