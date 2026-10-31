package cn.donting.javelin.editor.swing.panel;

/**
 * 面板生命周期契约（Editor_Swing_Design §5.2——对应 web DockPanelExpose 的
 * onShow/onHide 语义）。
 *
 * <p>由 {@code DockTabs} 在标签切换时调用：变活动 onShow、离活动 onHide。
 * 线程口径：调用在 EDT。面板实现方注意：订阅 {@code EditorEventBus} 的事件
 * 可能在引擎线程发出，回调里动 Swing 组件必须自行
 * {@code SwingUtilities.invokeLater}（先例=rectChange，规则见
 * Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public interface EditorPanel {

    /** 面板变为活动标签时触发 */
    default void onShow() {
    }

    /** 面板离开活动标签时触发 */
    default void onHide() {
    }
}
