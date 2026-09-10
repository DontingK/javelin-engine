/**
 * 通用树节点数据。
 *
 * <p>字段均为可选，实际字段名由使用组件通过 {@code nodeKey} / {@code labelKey} / {@code childrenKey} 指定。</p>
 */
export interface TreeNode {
  /** 节点唯一标识 */
  id?: string | number
  /** 节点显示文本 */
  name?: string
  /** 子节点列表 */
  children?: TreeNode[]
}
