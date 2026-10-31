package cn.donting.javelin.editor.swing.dock;

/**
 * Dock 布局树节点基类（Editor_Swing_Design §5.1：二分布局树）。
 *
 * <p>两实现：{@link SplitNode}（二分分割，恒两子）与 {@link TabNode}（标签组，
 * 只存面板 id）。组件实例与布局树解耦——树可整体序列化（M5 布局持久化）、
 * 面板状态生命周期归 {@code DockRoot} 缓存管。字段可变（拖拽重组/比重回写是
 * 既定后续），非序列化实体类。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public abstract class DockNode {

    /**
     * 子树是否含指定面板（布局策略用——如"含视口的一侧吸收尺寸变化"的
     * 弹性侧判定，Editor_Swing_Design §5.1）。
     *
     * @param node     树节点
     * @param panelId  面板 id
     * @return 子树含该面板返回 true
     */
    public static boolean containsPanel(DockNode node, String panelId) {
        if (node instanceof TabNode tab) {
            return tab.getPanelIds().contains(panelId);
        }
        SplitNode split = (SplitNode) node;
        return containsPanel(split.getLeft(), panelId) || containsPanel(split.getRight(), panelId);
    }
}
