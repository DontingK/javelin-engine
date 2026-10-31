package cn.donting.javelin.editor.swing.dock;

import cn.donting.javelin.editor.swing.panel.PanelDescriptor;
import cn.donting.javelin.editor.swing.panel.PanelRegistry;
import cn.donting.javelin.editor.plugin.EditorLang;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.HashMap;
import java.util.Map;

/**
 * Dock 根容器：持布局树并渲染（Editor_Swing_Design §5.1/§5.3）。
 *
 * <p>树→组件递归实例化：{@link SplitNode}→{@code DockSplitPane}、
 * {@link TabNode}→{@code DockTabs}。面板组件实例按 id 缓存于此（factory 只造
 * 一次；关闭 = detach 保留实例——面板内部状态不丢，M5 恢复直用）。
 * <b>关闭面板为组内局部重排（{@code DockTabs} 职责）——树结构与组件层级不动</b>：
 * 整树重挂会让画布经历 removeNotify→HWND 重建→交换链失效（"画布不 reparent"
 * 铁律的运行期延伸，2026-10-17 实测）。调用假定 EDT（组件域单线程）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class DockRoot extends JComponent {

    private DockNode tree;
    /** 面板 id → 组件实例（factory 只造一次；关闭 detach 复用不重建） */
    private final Map<String, JComponent> components = new HashMap<>();

    /**
     * 默认布局（Unity 口径，Editor_Swing_Design §5.1）：左中列上为（层级 ‖
     * 视口）、下为项目+控制台（<b>贯穿层级与视口区</b>）的底栏；右列属性全高。
     */
    public static DockRoot defaultLayout() {
        TabNode hierarchy = TabNode.of("hierarchy");
        TabNode viewport = TabNode.of("viewport");
        TabNode inspector = TabNode.of("inspector");
        TabNode bottom = TabNode.of("project", "console");
        SplitNode top = SplitNode.horizontal(hierarchy, viewport, 0.8);
        SplitNode left = SplitNode.vertical(top, bottom, 0.3);
        return new DockRoot(SplitNode.horizontal(left, inspector, 0.25));
    }

    public DockRoot(DockNode tree) {
        setLayout(new BorderLayout(0, 0));
        this.tree = tree;
        rebuild();
    }

    /** 整树重挂（结构变化入口；面板组件经缓存复用不重建） */
    public void rebuild() {
        removeAll();
        Component child = build(tree);
        if (child != null) {
            add(child, BorderLayout.CENTER);
        } else {
            add(emptyHint(), BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** 面板组件（缓存命中或 factory 首造；未注册抛异常 fail-fast） */
    JComponent componentFor(String panelId) {
        return components.computeIfAbsent(panelId, PanelRegistry::create);
    }

    /** 面板标题（注册表单源；未注册回退 id） */
    String titleOf(String panelId) {
        PanelDescriptor descriptor = PanelRegistry.get(panelId);
        return descriptor != null ? descriptor.getTitle() : panelId;
    }

    /** 面板可关性（注册表单源；未注册不可关） */
    boolean isClosable(String panelId) {
        PanelDescriptor descriptor = PanelRegistry.get(panelId);
        return descriptor != null && descriptor.isClosable();
    }

    /** 树→组件递归实例化（空子返回 null 由上层退化处理） */
    private Component build(DockNode node) {
        if (node instanceof SplitNode split) {
            Component left = build(split.getLeft());
            Component right = build(split.getRight());
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return new DockSplitPane(split, left, right);
        }
        if (node instanceof TabNode tab) {
            return tab.isEmpty() ? null : new DockTabs(tab, this);
        }
        return null;
    }

    /** 空态兜底（树为 null / 空组——关闭路径已不触发，构造期防御） */
    private JComponent emptyHint() {
        JLabel hint = new JLabel(EditorLang.text("dock.noPanel"), SwingConstants.CENTER);
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            hint.setForeground(dim);
        }
        return hint;
    }

}
