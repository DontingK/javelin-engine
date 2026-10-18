package com.javelin.editor.swing.inspector;

import com.javelin.core.obj.ObjectId;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.NodeTransformChangeEvent;
import com.javelin.editor.event.SceneChangedEvent;
import com.javelin.editor.event.SceneSelectEvent;
import com.javelin.editor.exception.TipException;
import com.javelin.editor.inspector.ComponentInspectorVo;
import com.javelin.editor.inspector.InspectorReflector;
import com.javelin.editor.inspector.NodeInspectorVo;
import com.javelin.editor.swing.inspector.widget.InsFormat;
import com.javelin.editor.swing.panel.EditorPanel;
import com.javelin.editor.window.MainFrame;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 属性面板（M4，Editor_Swing_Design §7.2——原 web EntityInspectorPanel 的 Swing
 * 实装；Inspector_Design v0.1~v0.3 通道面的 Java 直调重现）。
 *
 * <p><b>数据通道（§7.3 线程口径）</b>：选中变更（scene.select）→
 * {@code JavelinEngine.submitTask} 帧边界 {@link InspectorReflector#collect}
 * → invokeLater 整体重建（EDT 不活读引擎对象图）；回写=控件提交 → submitTask
 * ｛归属校验 → InspectorReflector.apply → markDirty｝（读写同队列串行不交错；
 * markDirty 原由已删桥层负责，本面板收口——净变脏翻转沿保证无事件风暴）。</p>
 *
 * <p><b>事件分工</b>：scene.changed（结构/保存后）→ 重拉（僵尸选中经引擎线程
 * 归属校验兜底清空）；scene.nodeTransformChanged（本面板为首个消费方——Gizmo
 * 拖拽帧级推送）→ Transform 三行定点刷新（四元数→欧拉 getEulerAnglesXYZ，
 * 收集侧 serialize 同口径），控件值等价跳过防回声。</p>
 *
 * <p><b>编辑保护</b>：任一控件编辑中（焦点内且文本已改）重建延后
 * （pendingRefresh），提交收尾补拉——防结构变更重拉吞掉正在输入的值。组件折叠
 * 态按组件名跨重建记忆。事件订阅构造期一次（面板 DockRoot 缓存常驻无退订，
 * HierarchyPanel 同口径）；订阅回调在总线泵线程，一律 invokeLater 跳 EDT。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class InspectorPanel extends JComponent implements EditorPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(InspectorPanel.class);

    /** 无选中占位文案（选中 null 是正常态——非层级面板的未就绪，无重试） */
    private static final String EMPTY_TEXT = "未选中任何对象";

    private final JLabel emptyLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示节点（拉取/归属校验锚点；null=空态） */
    private Node selectedNode;
    private NodeHeaderPanel nodeHeader;
    private final List<ComponentSection> sections = new ArrayList<>();
    /** 组件名 → 折叠态（跨重建保留——scene.changed 重拉不丢用户折叠意图） */
    private final Map<String, Boolean> collapsedByName = new HashMap<>();
    /** 编辑保护挂起重拉（任一控件编辑中时置位，提交收尾补拉） */
    private boolean pendingRefresh;

    public InspectorPanel() {
        setLayout(new BorderLayout(0, 0));
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            emptyLabel.setForeground(dim);
        }
        add(emptyLabel, BorderLayout.CENTER);
        subscribeEvents();
    }

    // ------------------------------------------------------------------ 事件订阅（总线泵线程 → EDT）

    private void subscribeEvents() {
        EditorEventBus.subscribe(SceneSelectEvent.class, e -> SwingUtilities.invokeLater(
                () -> onSelectionChanged(e.getNode())));
        EditorEventBus.subscribe(SceneChangedEvent.class,
                e -> SwingUtilities.invokeLater(this::requestRefresh));
        EditorEventBus.subscribe(NodeTransformChangeEvent.class, e -> SwingUtilities.invokeLater(
                () -> applyTransform(e)));
    }

    // ------------------------------------------------------------------ 选中驱动

    private void onSelectionChanged(Node node) {
        if (node == selectedNode) {
            return;
        }
        selectedNode = node;
        if (node == null) {
            showEmpty();
        } else {
            requestRefresh();
        }
    }

    /** 请求快照：引擎线程帧边界 collect（含归属校验）→ EDT 整体重建 */
    private void requestRefresh() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过 Inspector 拉取");
            return;
        }
        Node node = selectedNode;
        if (node == null) {
            showEmpty();
            return;
        }
        editor.submitTask(() -> {
            // 归属校验（TransformWatchAppState 先例）：node.getScene() 挂的是 GameScene
            // （用户场景），getEditorScene() 是包装器——须比 getGameScene() 本体
            GameScene userScene = editor.getEditorScene().getGameScene();
            if (userScene == null || node.getScene() != userScene) {
                // 僵尸选中（已删/换场景）——引用化拍板口径：归属校验兜底
                SwingUtilities.invokeLater(this::showEmpty);
                return;
            }
            NodeInspectorVo vo = InspectorReflector.collect(node);
            SwingUtilities.invokeLater(() -> applyVo(vo));
        });
    }

    /** 快照应用（EDT）：拉取期间选中已前进则丢弃；编辑中则挂起重拉 */
    private void applyVo(NodeInspectorVo vo) {
        if (vo.node != selectedNode) {
            return;
        }
        if (isAnyEditing()) {
            pendingRefresh = true;
            return;
        }
        rebuild(vo);
    }

    /** 整体重建：节点头 + 组件分组（折叠态记忆恢复） */
    private void rebuild(NodeInspectorVo vo) {
        rememberCollapsed();
        JPanel content = new ScrollableContent();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        nodeHeader = new NodeHeaderPanel(vo, this::commitField);
        content.add(nodeHeader);
        sections.clear();
        for (ComponentInspectorVo componentVo : vo.components) {
            content.add(Box.createVerticalStrut(2));
            ComponentSection section = new ComponentSection(vo.node, componentVo, this::commitField,
                    collapsedByName.getOrDefault(componentVo.name, false));
            sections.add(section);
            content.add(section);
        }
        if (scrollPane == null) {
            remove(emptyLabel);
            scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            // 宽度恒跟视口（tracksWidth=true 兜底）：窄面板收缩/换行，不出水平滚动条
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            add(scrollPane, BorderLayout.CENTER);
        } else {
            scrollPane.setViewportView(content);
        }
        revalidate();
        repaint();
    }

    /**
     * 内容容器（Scrollable 双向钉口径）：宽度跟随视口——控件列收缩 / VectorRow
     * FlowLayout 换行，窄面板永不水平滚动；高度按 preferred——不满一屏不被
     * viewport 拉伸占满（普通 JPanel 会被双向拉伸：高度被拉满致 BoxLayout 摊大
     * 间隔、宽度按单行 preferred 致水平滚动条，2026-10-18 实测修正）。
     */
    private static final class ScrollableContent extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 20;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** 重建前记忆当前折叠态（重建后经构造参数恢复） */
    private void rememberCollapsed() {
        for (ComponentSection section : sections) {
            collapsedByName.put(section.getComponentName(), section.isCollapsed());
        }
    }

    private void showEmpty() {
        selectedNode = null;
        nodeHeader = null;
        sections.clear();
        if (scrollPane != null) {
            remove(scrollPane);
            scrollPane = null;
            add(emptyLabel, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }

    // ------------------------------------------------------------------ 回写链（EDT 入口 → 引擎帧边界）

    private void commitField(Node owner, ObjectId host, String fieldName, String valueType, String wireValue) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，丢弃 Inspector 回写");
            return;
        }
        editor.submitTask(() -> {
            try {
                // 归属校验同 requestRefresh：比 getGameScene() 本体（GameScene）
                GameScene userScene = editor.getEditorScene().getGameScene();
                if (userScene == null || owner.getScene() != userScene) {
                    // 僵尸提交（拉取后节点被删/换场景）静默丢弃
                    return;
                }
                InspectorReflector.apply(host, fieldName, valueType, wireValue);
                // 原桥层职责收口：回写即置脏（净变脏翻转沿，无事件风暴）
                editor.getEditorScene().markDirty();
            } catch (TipException e) {
                SwingUtilities.invokeLater(() -> showTip(e.getMessage()));
            }
        });
        if (pendingRefresh) {
            pendingRefresh = false;
            requestRefresh();
        }
    }

    /** 回写失败提示（状态栏；UI 侧钳制前置使此路径以不可达兜底为主） */
    private void showTip(String message) {
        MainFrame frame = MainFrame.getInstance();
        if (frame != null && frame.getStatusBar() != null) {
            frame.getStatusBar().setText(message);
        } else {
            LOGGER.warn("Inspector 回写失败: {}", message);
        }
    }

    // ------------------------------------------------------------------ Transform 定点刷新（Gizmo 拖拽联动）

    private void applyTransform(NodeTransformChangeEvent event) {
        if (event.getNode() != selectedNode || nodeHeader == null) {
            return;
        }
        FieldEditor position = findEditor("localPosition");
        FieldEditor rotation = findEditor("localRotation");
        FieldEditor scale = findEditor("localScale");
        if (position != null) {
            position.setValue(InsFormat.join(event.getPosition()));
        }
        if (rotation != null) {
            rotation.setValue(eulerWire(event.getRotation()));
        }
        if (scale != null) {
            scale.setValue(InsFormat.join(event.getScale()));
        }
    }

    private FieldEditor findEditor(String fieldName) {
        for (ComponentSection section : sections) {
            FieldEditor editor = section.getEditor(fieldName);
            if (editor != null) {
                return editor;
            }
        }
        return null;
    }

    /** 四元数 → 欧拉角度数串（getEulerAnglesXYZ——收集侧 serialize 同口径） */
    private static String eulerWire(float[] quaternion) {
        Vector3f euler = new Vector3f();
        new Quaternionf(quaternion[0], quaternion[1], quaternion[2], quaternion[3])
                .getEulerAnglesXYZ(euler);
        return InsFormat.join(
                (float) Math.toDegrees(euler.x),
                (float) Math.toDegrees(euler.y),
                (float) Math.toDegrees(euler.z));
    }

    private boolean isAnyEditing() {
        if (nodeHeader != null && nodeHeader.isEditing()) {
            return true;
        }
        for (ComponentSection section : sections) {
            if (section.isEditing()) {
                return true;
            }
        }
        return false;
    }
}
