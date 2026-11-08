package cn.donting.javelin.editor.swing.viewport;

import cn.donting.javelin.editor.appstate.gizmo.TransformGizmoAppState;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.SvgIcon;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * 场景视图工具栏（Editor_Swing_Design §6——web 版 SceneViewportPanel 工具栏
 * 的 Swing 化，语义不变）。
 *
 * <p>Tools 组：移动/旋转/缩放三工具齐备（2026-11-08 解禁后两者）；竖分隔 + 局部/
 * 世界二段切换（缩放工具恒局部，坐标空间对它无效——Unity/Babylon 口径）。本地态
 * 即点即亮、不持久化（重启回 移动+世界）。写通道 = {@link TransformGizmoAppState}
 * 的 static volatile 意图镜像（EDT 直写安全，先例=GizmoMethod/GridMenuCommand；
 * 引擎线程 update 帧内消费，未知工具名由 AppState 侧查名落空维持现状）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class SceneViewToolbar extends JComponent {

    /** 工具栏高度（对齐标签条 24 规格） */
    private static final int HEIGHT = 24;

    public SceneViewToolbar() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 2, 0));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()));

        JToggleButton move = toolButton("move", EditorLang.text("toolbar.moveTool"), true);
        JToggleButton rotate = toolButton("rotate", EditorLang.text("toolbar.rotateTool"), true);
        JToggleButton scale = toolButton("scale", EditorLang.text("toolbar.scaleTool"), true);
        ButtonGroup tools = new ButtonGroup();
        tools.add(move);
        tools.add(rotate);
        tools.add(scale);
        move.setSelected(true);
        move.addActionListener(e -> TransformGizmoAppState.setDesiredToolName("move"));
        rotate.addActionListener(e -> TransformGizmoAppState.setDesiredToolName("rotate"));
        scale.addActionListener(e -> TransformGizmoAppState.setDesiredToolName("scale"));
        add(move);
        add(rotate);
        add(scale);

        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 14));
        add(separator);

        JToggleButton local = toggle(EditorLang.text("toolbar.local"),
                EditorLang.text("toolbar.localTip"));
        JToggleButton world = toggle(EditorLang.text("toolbar.world"),
                EditorLang.text("toolbar.worldTip"));
        ButtonGroup space = new ButtonGroup();
        space.add(local);
        space.add(world);
        world.setSelected(true);
        local.addActionListener(e -> TransformGizmoAppState.setLocalSpace(true));
        world.addActionListener(e -> TransformGizmoAppState.setLocalSpace(false));
        add(local);
        add(world);
    }

    @Override
    public Dimension getPreferredSize() {
        // 固定条高（子项高度由 FlowLayout 居中承担）；宽走子项自然宽
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    /** 图标工具钮（icon=/icon/{name}.svg；间距规范：margin ≤2） */
    private static JToggleButton toolButton(String icon, String tooltip, boolean enabled) {
        JToggleButton button = new JToggleButton(SvgIcon.of(icon, 14));
        button.setToolTipText(tooltip);
        button.setEnabled(enabled);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 2, 1, 2));
        return button;
    }

    /** 文本切换钮（局部/世界） */
    private static JToggleButton toggle(String text, String tooltip) {
        JToggleButton button = new JToggleButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 2, 1, 2));
        return button;
    }

    private static Color borderColor() {
        // 取色只依赖确定存在的键（亮暗两档皆有）；兜底主题中性中灰
        Color color = UIManager.getColor("Component.borderColor");
        if (color == null) {
            color = UIManager.getColor("Separator.foreground");
        }
        return color != null ? color : new Color(0xBB, 0xBB, 0xBB);
    }
}
