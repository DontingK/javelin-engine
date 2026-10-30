package com.javelin.editor.swing.inspector;

import com.javelin.core.scene.Node;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.ProjectSelectEvent;
import com.javelin.editor.event.SceneSelectEvent;
import com.javelin.editor.selection.EditorSelection;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.swing.panel.EditorPanel;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.io.File;

/**
 * 检查器外壳（Inspector_Design v0.5，Editor_Swing_Design §7.2）：Dock 面板本体
 * 只做分发与空态——选中变更（scene.select/project.select 双事件触发，拉态单源
 * 读 {@link EditorSelection}，连发幂等）经 {@link InspectorRegistry} 查 provider、
 * 换 {@link InspectorContent}（统一接管 Node 与资产文件，2026-10-21 拍板，Unity
 * 多态 Inspector 口径）。无 provider 显示「无检查器」灰提示，无选中显示空态文案；
 * 内容件生命周期契约见 InspectorContent。事件订阅构造期一次（DockRoot 缓存
 * 常驻），回调在总线泵线程，一律 invokeLater 跳 EDT。
 *
 * @author javelin
 * @create 2026/10/21
 */
public class InspectorPanel extends JComponent implements EditorPanel {

    /** 无选中占位文案（选中 null 是正常态——非层级面板的未就绪，无重试） */
    private static final String EMPTY_TEXT = EditorLang.text("inspector.empty");
    /** 有选中但无接管 provider（未支持文件/目录）灰提示——Unity 对不支持资产口径 */
    private static final String UNSUPPORTED_TEXT = EditorLang.text("inspector.unsupported");

    private final JLabel emptyLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    /** 当前内容件（null=空态） */
    private InspectorContent content;
    /** 当前接管 provider（同 provider 换目标不重建内容件） */
    private InspectorProvider provider;

    public InspectorPanel() {
        setLayout(new BorderLayout(0, 0));
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            emptyLabel.setForeground(dim);
        }
        add(emptyLabel, BorderLayout.CENTER);
        EditorEventBus.subscribe(SceneSelectEvent.class,
                e -> SwingUtilities.invokeLater(this::dispatch));
        EditorEventBus.subscribe(ProjectSelectEvent.class,
                e -> SwingUtilities.invokeLater(this::dispatch));
    }

    // ------------------------------------------------------------------ 选中分发（总线泵线程 → EDT）

    /** 选中分发（EDT）：拉态单源——事件只当触发器，双事件连发幂等 */
    private void dispatch() {
        Node node = EditorSelection.getSelectedNode();
        File path = EditorSelection.getSelectedPath();
        InspectorSelection selection = new InspectorSelection(node, path);
        InspectorProvider matched = InspectorRegistry.find(selection);
        if (matched == null) {
            showEmpty(selection);
            return;
        }
        if (matched != provider) {
            swapContent(matched, selection);
        } else if (content != null) {
            content.onSelection(selection);
        }
    }

    /** 换内容件（EDT）：旧件 onDetach → create → 挂载 → onAttach → onSelection */
    private void swapContent(InspectorProvider matched, InspectorSelection selection) {
        if (content != null) {
            remove(content.component());
            content.onDetach();
        } else {
            remove(emptyLabel);
        }
        provider = matched;
        content = matched.create();
        add(content.component(), BorderLayout.CENTER);
        content.onAttach();
        content.onSelection(selection);
        revalidate();
        repaint();
    }

    /** 空态（EDT）：无选中与「有选中无 provider」分档文案 */
    private void showEmpty(InspectorSelection selection) {
        if (content != null) {
            remove(content.component());
            content.onDetach();
            content = null;
            provider = null;
            if (emptyLabel.getParent() != this) {
                add(emptyLabel, BorderLayout.CENTER);
            }
            revalidate();
            repaint();
        }
        boolean unsupported = selection.node() != null || selection.file() != null;
        emptyLabel.setText(unsupported ? UNSUPPORTED_TEXT : EMPTY_TEXT);
    }
}
