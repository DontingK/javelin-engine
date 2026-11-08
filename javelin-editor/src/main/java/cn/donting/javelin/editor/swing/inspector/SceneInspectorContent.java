package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.swing.SvgIcon;
import cn.donting.javelin.editor.swing.inspector.widget.CollapsiblePanel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Objects;

/**
 * 场景检查器内容件（.scene 键）：场景分组（文件身份行）。场景级参数不在这里——
 * 环境设置是挂在节点上的普通组件，走节点检查器（2026-11-08 拍板，原 skybox 行
 * 与其后的环境组件节均撤出）。
 *
 * <p>选中非当前打开场景时只读态（file 对不上 editorScene.getGameSceneFile——
 * 内存活实例只有一份）。</p>
 *
 * @author javelin
 * @create 2026/11/03
 */
public class SceneInspectorContent extends JComponent implements InspectorContent {

    private static final String EMPTY_TEXT = EditorLang.text("scene.inspector.notCurrent");

    private final JLabel statusLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件 */
    private File currentFile;

    public SceneInspectorContent() {
        setLayout(new BorderLayout(0, 0));
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            statusLabel.setForeground(dim);
        }
        add(statusLabel, BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public void onSelection(InspectorSelection selection) {
        File file = selection.file();
        if (file == null || file.equals(currentFile)) {
            return;
        }
        currentFile = file;
        SwingUtilities.invokeLater(this::build);
    }

    /** 组装（EDT）：当前打开场景=场景分组文件行；否则只读态 */
    private void build() {
        File file = currentFile;
        if (file == null) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        EditorScene editorScene = editor != null ? editor.getEditorScene() : null;
        GameScene gameScene = editorScene != null ? editorScene.getGameScene() : null;
        File openFile = editorScene != null ? editorScene.getGameSceneFile() : null;

        boolean editable = gameScene != null && openFile != null
                && Objects.equals(normalize(file), normalize(openFile));
        if (!editable) {
            showStatus();
            return;
        }

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(sceneSection(file));
        mount(content);
    }

    /** 场景分组（固定不可折叠）：文件（暗） */
    private JComponent sceneSection(File file) {
        FieldGrid grid = new FieldGrid();
        grid.addDim(EditorLang.text("scene.inspector.file"), file.getName());
        CollapsiblePanel section = new CollapsiblePanel(
                EditorLang.text("scene.inspector.section"), null, titleIcon("scene"), grid);
        section.setCollapsible(false);
        return section;
    }

    // ------------------------------------------------------------------ 布局件

    /** 分组头图标（16px，缺资源不占位） */
    private static Icon titleIcon(String name) {
        if (name == null) {
            return null;
        }
        SvgIcon icon = SvgIcon.of(name, 16);
        return icon.isAvailable() ? icon : null;
    }

    /** 路径归一（大小写/分隔符差异不影响「当前场景」判定） */
    private static File normalize(File file) {
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file.getAbsoluteFile();
        }
    }

    /** 两列字段行网格（TextureInspectorContent.FieldGrid 同口径；本面板只需暗淡值行） */
    private static final class FieldGrid extends JPanel {

        private int row;

        private FieldGrid() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }

        private void addDim(String label, String value) {
            JLabel labelComponent = new JLabel(label);
            JLabel valueComponent = new JLabel(value);
            Color dimColor = UIManager.getColor("Label.disabledForeground");
            if (dimColor != null) {
                labelComponent.setForeground(dimColor);
                valueComponent.setForeground(dimColor);
            }
            GridBagConstraints labelGbc = new GridBagConstraints();
            labelGbc.gridx = 0;
            labelGbc.gridy = row;
            labelGbc.anchor = GridBagConstraints.WEST;
            labelGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 4);
            add(labelComponent, labelGbc);

            GridBagConstraints controlGbc = new GridBagConstraints();
            controlGbc.gridx = 1;
            controlGbc.gridy = row;
            controlGbc.fill = GridBagConstraints.HORIZONTAL;
            controlGbc.weightx = 1;
            controlGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 0);
            add(valueComponent, controlGbc);
            row++;
        }
    }

    // ------------------------------------------------------------------ 壳件

    /** 挂载内容（替换状态文案） */
    private void mount(JComponent content) {
        if (scrollPane == null) {
            remove(statusLabel);
            scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            add(scrollPane, BorderLayout.CENTER);
        } else {
            scrollPane.setViewportView(content);
        }
        revalidate();
        repaint();
    }

    /** 只读态文案（非当前打开场景） */
    private void showStatus() {
        if (scrollPane != null) {
            remove(scrollPane);
            scrollPane = null;
            add(statusLabel, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }
}
