package com.javelin.editor.swing.dock;

import com.javelin.editor.swing.panel.EditorPanel;
import com.javelin.editor.swing.panel.PanelDescriptor;
import com.javelin.editor.swing.panel.PanelRegistry;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TabNode} 渲染件：标签条 + 内容区（Editor_Swing_Design §5.3）。
 *
 * <p>内容组件经 {@link DockRoot} 缓存按面板 id 取（factory 只造一次——关闭 =
 * detach 保留实例，面板内部状态不丢，Editor_Swing_Design §10-6）。切换走
 * {@link EditorPanel} onShow/onHide 生命周期（对齐 web DockPanelExpose 语义）。
 * 标签条显隐：多面板或含可关项必显示，且<b>一旦显示过就保持</b>（组曾是多标签
 * 停靠区，关闭到剩一个不可关面板也保留标签身份——2026-10-17 实测修正：关控制台
 * 后项目标签条误被"单面板不可关无缝"规则摘除）；天生单面板不可关（视口/属性）
 * 无缝——Editor_Swing_Design §6 的 viewport 特化接入位。</p>
 *
 * <p><b>关闭语义（M1）</b>：组内局部重排——树结构不动、DockRoot 不整树重建
 * （整树重挂会让画布经历 removeNotify→HWND 重建→交换链失效，"画布不
 * reparent"铁律的运行期延伸，2026-10-17 实测"关属性面板即坏"）。关最后一个
 * 面板保留空组占位，不塌缩；塌缩与「窗口」域恢复归 M5（配 RHI surface 重建）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
final class DockTabs extends JComponent {

    private final TabNode model;
    private final DockRoot root;
    private final JPanel content = new JPanel(new BorderLayout(0, 0));
    private TabStrip strip;
    /** 标签条曾显示标志（显示过即保持——组身份不随关闭消失） */
    private boolean stripEverShown;

    DockTabs(TabNode model, DockRoot root) {
        this.model = model;
        this.root = root;
        setLayout(new BorderLayout(0, 0));
        rebuildStrip();
        add(content, BorderLayout.CENTER);
        showActive();
    }

    /** 多面板或单面板可关 → 显示条；单面板不可关（视口）→ 无缝 */
    private boolean stripVisible() {
        if (model.getPanelIds().size() > 1) {
            return true;
        }
        for (String panelId : model.getPanelIds()) {
            if (root.isClosable(panelId)) {
                return true;
            }
        }
        return false;
    }

    /** 标签条重建（关闭面板后条目集变化——TabStrip 条目不可变，整条重建） */
    private void rebuildStrip() {
        if (strip != null) {
            remove(strip);
            strip = null;
        }
        if (stripVisible() || stripEverShown) {
            stripEverShown = true;
            strip = new TabStrip(buildItems(), model.getActiveIndex(), new TabStrip.Listener() {
                @Override
                public void onTabSelected(int index) {
                    if (index != model.getActiveIndex()) {
                        model.setActiveIndex(index);
                        showActive();
                    }
                }

                @Override
                public void onTabClose(int index) {
                    closePanel(index);
                }
            });
            add(strip, BorderLayout.NORTH);
        }
    }

    private List<TabStrip.TabItem> buildItems() {
        List<TabStrip.TabItem> items = new ArrayList<>();
        for (String panelId : model.getPanelIds()) {
            items.add(new TabStrip.TabItem(root.titleOf(panelId), root.isClosable(panelId)));
        }
        return items;
    }

    @Override
    public Dimension getMinimumSize() {
        // 组最小尺寸 = chrome 自然最小 ∨ 组内面板声明最小取最大（面板描述符契约，
        // 2026-10-17：左/右/底区域最小宽高的约束源）——分割条钳制与窗口收缩
        // 都走标准 minimum 上汇链生效，面板换实装（M2+）时约束跟描述符不丢
        Dimension size = new Dimension(super.getMinimumSize());
        for (String panelId : model.getPanelIds()) {
            PanelDescriptor descriptor = PanelRegistry.get(panelId);
            if (descriptor != null) {
                Dimension minimum = descriptor.getMinimumSize();
                if (minimum != null) {
                    size.width = Math.max(size.width, minimum.width);
                    size.height = Math.max(size.height, minimum.height);
                }
            }
        }
        return size;
    }

    /**
     * 关闭面板：模型摘除 + 本组局部重排（树结构不动）。活动项被关时挂载组件
     * onHide + detach（实例留在 DockRoot 缓存，M5 恢复直用）。
     */
    private void closePanel(int index) {
        boolean wasActive = index == model.getActiveIndex();
        model.removePanel(model.getPanelIds().get(index));
        if (model.isEmpty()) {
            detachActive();
            rebuildStrip();
            content.add(emptyHint(), BorderLayout.CENTER);
        } else if (wasActive) {
            detachActive();
            rebuildStrip();
            showActive();
        } else {
            rebuildStrip();
        }
        revalidate();
        repaint();
    }

    /** 摘除挂载中的面板组件（onHide + detach；实例留缓存） */
    private void detachActive() {
        if (content.getComponentCount() > 0) {
            JComponent current = (JComponent) content.getComponent(0);
            if (current instanceof EditorPanel panel) {
                panel.onHide();
            }
            content.removeAll();
        }
    }

    /** 渲染活动面板（摘旧挂新；EditorPanel 生命周期调用） */
    private void showActive() {
        String panelId = model.getActivePanelId();
        detachActive();
        if (panelId != null) {
            JComponent component = root.componentFor(panelId);
            content.add(component, BorderLayout.CENTER);
            if (component instanceof EditorPanel panel) {
                panel.onShow();
            }
        }
        revalidate();
        repaint();
    }

    /** 空组占位（全部面板已关闭；M5「窗口」域恢复落地前兜底） */
    private static JComponent emptyHint() {
        JLabel hint = new JLabel("面板已关闭", SwingConstants.CENTER);
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            hint.setForeground(dim);
        }
        return hint;
    }
}
