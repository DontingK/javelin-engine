package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.obj.ObjectId;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.NodeTransformChangeEvent;
import cn.donting.javelin.editor.event.SceneChangedEvent;
import cn.donting.javelin.editor.event.Subscription;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.inspector.ComponentInspectorVo;
import cn.donting.javelin.editor.inspector.InspectorReflector;
import cn.donting.javelin.editor.inspector.InspectorValueType;
import cn.donting.javelin.editor.inspector.NodeInspectorVo;
import cn.donting.javelin.editor.swing.inspector.widget.InsFormat;
import cn.donting.javelin.editor.window.MainFrame;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点检查器内容件（node 键，Inspector_Design v0.5）：M4 面板直调逻辑自
 * InspectorPanel 平移，数据通道/回写链/编辑保护口径不变。差异仅在事件分工——
 * 选中驱动归外壳，本件订阅 scene.changed（重拉）与 nodeTransformChanged
 * （Transform 三行定点刷新），onAttach 订 / onDetach 退。
 *
 * <p>数据通道：onSelection → {@code JavelinEditor.submitTask} 帧边界
 * {@link InspectorReflector#collect} → invokeLater 整体重建（EDT 不活读引擎
 * 对象图）；回写=控件提交 → submitTask ｛归属校验 → apply → markDirty｝。
 * 编辑保护：任一控件编辑中重建延后（pendingRefresh），提交收尾补拉；组件折叠
 * 态按组件名跨重建记忆。订阅回调在总线泵线程，一律 invokeLater 跳 EDT。</p>
 *
 * @author javelin
 * @create 2026/10/21
 */
public class NodeInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeInspectorContent.class);

    /** 内部空态文案（僵尸节点兜底；「无选中」常态归外壳） */
    private static final String EMPTY_TEXT = EditorLang.text("inspector.empty");

    private final JLabel emptyLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    private final List<Subscription> subscriptions = new ArrayList<>();
    /** 当前展示节点（拉取/归属校验锚点；null=空态） */
    private Node selectedNode;
    private NodeHeaderPanel nodeHeader;
    private final List<ComponentSection> sections = new ArrayList<>();
    /** 组件名 → 折叠态（跨重建保留——scene.changed 重拉不丢用户折叠意图） */
    private final Map<String, Boolean> collapsedByName = new HashMap<>();
    /** 编辑保护挂起重拉（任一控件编辑中时置位，提交收尾补拉） */
    private boolean pendingRefresh;

    public NodeInspectorContent() {
        setLayout(new BorderLayout(0, 0));
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            emptyLabel.setForeground(dim);
        }
        add(emptyLabel, BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return this;
    }

    // ------------------------------------------------------------------ 生命周期（EDT）

    @Override
    public void onAttach() {
        subscriptions.add(EditorEventBus.subscribe(SceneChangedEvent.class,
                e -> SwingUtilities.invokeLater(this::requestRefresh)));
        subscriptions.add(EditorEventBus.subscribe(NodeTransformChangeEvent.class,
                e -> SwingUtilities.invokeLater(() -> applyTransform(e))));
    }

    @Override
    public void onDetach() {
        for (Subscription subscription : subscriptions) {
            subscription.dispose();
        }
        subscriptions.clear();
    }

    @Override
    public void onSelection(InspectorSelection selection) {
        Node node = selection.node();
        if (node == selectedNode) {
            return;
        }
        selectedNode = node;
        if (node == null) {
            // 兜底（空态归外壳，正常不达）
            showEmpty();
            return;
        }
        requestRefresh();
    }

    // ------------------------------------------------------------------ 快照拉取与重建

    /** 请求快照：引擎线程帧边界 collect（含归属校验）→ EDT 整体重建 */
    private void requestRefresh() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过检查器拉取");
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
        InsScrollablePanel content = new InsScrollablePanel();
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

    /** 重建前记忆当前折叠态（重建后经构造参数恢复） */
    private void rememberCollapsed() {
        for (ComponentSection section : sections) {
            collapsedByName.put(section.getComponentName(), section.isCollapsed());
        }
    }

    /** 清空为内部空态（僵尸选中兜底；常态清空由外壳接管） */
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
            LOGGER.warn("编辑器实例未创建，丢弃检查器回写");
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
        // 资产行提交可能改变行结构（mesh 行换绑 → 材质列表按子网格数对齐），提交后
        // 补拉重建行数；apply 与 collect 同走 submitTask FIFO，先应用后收集
        if (InspectorValueType.GUID_ASSET.name().equals(valueType)
                || InspectorValueType.GUID_ASSET_LIST.name().equals(valueType)
                || pendingRefresh) {
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
            LOGGER.warn("检查器回写失败: {}", message);
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
