package cn.donting.javelin.editor.swing.viewport;

import cn.donting.javelin.editor.window.cef.CanvasSurface;

import javax.swing.JComponent;
import java.awt.BorderLayout;

/**
 * 视口宿主：Vulkan 画布常驻位（Editor_Swing_Design §6）。
 *
 * <p>画布唯一真身，{@code ViewportTabs} 持有、<b>不 reparent</b>（换 parent =
 * removeNotify → HWND 释放重建 → 交换链重建，一票否决；运行期结构操作同守此
 * 律——关闭面板走组内局部重排零重挂，见 §10-6 坑③）。尺寸由 Swing 布局收敛，
 * resize 走 CanvasSurface 自身 ComponentAdapter。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class ViewportHost extends JComponent {

    public ViewportHost(CanvasSurface canvas) {
        setLayout(new BorderLayout(0, 0));
        add(canvas, BorderLayout.CENTER);
    }
}
