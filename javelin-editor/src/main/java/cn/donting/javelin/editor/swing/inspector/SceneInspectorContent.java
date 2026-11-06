package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.swing.SvgIcon;
import cn.donting.javelin.editor.swing.inspector.widget.AssetInput;
import cn.donting.javelin.editor.swing.inspector.widget.CollapsiblePanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 场景检查器内容件（.scene 键，批次⑤步骤三 3c）：场景级字段编辑——skybox 行
 * （AssetInput 换绑 .hdr/.pano，None 摘除合法）。
 *
 * <p><b>编辑载体 = 当前打开的 EditorScene.gameScene 实例</b>（区别于
 * .texture/.mat 的文本私有实例——场景字段改的是内存活实例：桥每帧 pull 天空盒
 * 引用，改后下一帧即生效视口换背景，无重导链）；持久化经 markDirty → 用户
 * file.save 走既有场景保存链（与场景树编辑同口径，改即写盘属 .texture 防抖
 * 那类资产语义，场景有 dirty 机制不复用）。选中非当前打开场景时只读展示
 * （file 对不上 editorScene.getGameSceneFile 即挂只读态——内存活实例只有一份，
 * 磁盘态解析只读预览归后续需要再扩）。</p>
 *
 * <p>线程契约：onSelection（EDT）直查活实例（无后台解析——活实例内存现成，
 * 不走 TextImporter）；skybox guid 解实例在 EDT loadGuid（Library 二进制小读
 * 与 .texture 图行同款）。</p>
 *
 * @author javelin
 * @create 2026/11/03
 */
public class SceneInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneInspectorContent.class);

    private static final String EMPTY_TEXT = EditorLang.text("scene.inspector.notCurrent");

    private final JLabel statusLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件 */
    private File currentFile;
    /** skybox 行输入（只读态拒绝提交回滚用） */
    private AssetInput skyboxInput;

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

    @Override
    public void onDetach() {
        // 无防抖/后台线程，无需收尾
    }

    /** 组装（EDT）：当前打开场景=可编辑 skybox 行；否则只读态 */
    private void build() {
        File file = currentFile;
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

        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(sceneSection(gameScene, file));
        mount(content);
    }

    /** 场景分组（固定不可折叠）：文件（暗）/ 天空盒行 */
    private JComponent sceneSection(GameScene gameScene, File file) {
        FieldGrid grid = new FieldGrid();
        grid.addDim(EditorLang.text("scene.inspector.file"), file.getName());
        grid.add(EditorLang.text("scene.inspector.skybox"), skyboxInput(gameScene));
        CollapsiblePanel section = new CollapsiblePanel(
                EditorLang.text("scene.inspector.section"), null, titleIcon("scene"), grid);
        section.setCollapsible(false);
        return section;
    }

    /** 天空盒行（拖放/选择窗换绑；当前值=CubemapImage guid，None=摘除合法回退纯色清屏） */
    private JComponent skyboxInput(GameScene gameScene) {
        AssetInput input = new AssetInput(CubemapImage.class, this::onSkyboxCommit);
        CubemapImage skybox = gameScene.getSkybox();
        input.setValue(skybox != null ? skybox.getGuid() : null);
        this.skyboxInput = input;
        return input;
    }

    /**
     * 天空盒换绑提交（EDT）：guid 经 loadGuid 解实例校验；None 摘除合法（SKYBOX
     * 视口回退纯色清屏）；空串=内存创建语义对场景字段无意义拒绝回滚。直写活
     * 实例 + markDirty（桥下帧推送懒上传/摘除；持久化归用户 file.save）。
     */
    private void onSkyboxCommit(String newGuid) {
        JavelinEditor editor = JavelinEditor.getInstance();
        EditorScene editorScene = editor != null ? editor.getEditorScene() : null;
        GameScene gameScene = editorScene != null ? editorScene.getGameScene() : null;
        if (gameScene == null) {
            return;
        }
        CubemapImage old = gameScene.getSkybox();
        String oldGuid = old != null ? old.getGuid() : null;

        if (newGuid != null && newGuid.isBlank()) {
            LOGGER.warn("场景天空盒不支持内存创建语义，拒绝空串提交");
            skyboxInput.setValue(oldGuid);
            return;
        }
        CubemapImage asset = null;
        if (newGuid != null) {
            asset = editor.getAssetManager().loadGuid(newGuid, CubemapImage.class);
            if (asset == null) {
                LOGGER.warn("CubemapImage 加载失败（Library 缺产物？），拒绝换绑: guid={}", newGuid);
                skyboxInput.setValue(oldGuid);
                return;
            }
        }
        gameScene.setSkybox(asset);
        editorScene.markDirty();
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

    /** 两列字段行网格（TextureInspectorContent.FieldGrid 同口径） */
    private static final class FieldGrid extends JPanel {

        private int row;

        private FieldGrid() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }

        private void add(String label, JComponent control) {
            place(new JLabel(label), control, false);
        }

        private void addDim(String label, String value) {
            place(new JLabel(label), new JLabel(value), true);
        }

        private void place(JLabel label, JComponent control, boolean dim) {
            if (dim) {
                Color dimColor = UIManager.getColor("Label.disabledForeground");
                if (dimColor != null) {
                    label.setForeground(dimColor);
                    control.setForeground(dimColor);
                }
            }
            GridBagConstraints labelGbc = new GridBagConstraints();
            labelGbc.gridx = 0;
            labelGbc.gridy = row;
            labelGbc.anchor = GridBagConstraints.WEST;
            labelGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 4);
            add(label, labelGbc);

            GridBagConstraints controlGbc = new GridBagConstraints();
            controlGbc.gridx = 1;
            controlGbc.gridy = row;
            controlGbc.fill = GridBagConstraints.HORIZONTAL;
            controlGbc.weightx = 1;
            controlGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 0);
            add(control, controlGbc);
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
