package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.obj.ObjectId;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.NodeTransformChangeEvent;
import cn.donting.javelin.editor.event.SceneChangedEvent;
import cn.donting.javelin.editor.event.Subscription;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.exception.TipException;
import cn.donting.javelin.editor.inspector.AddComponentCatalog;
import cn.donting.javelin.editor.inspector.AddComponentVo;
import cn.donting.javelin.editor.inspector.ComponentInspectorVo;
import cn.donting.javelin.editor.inspector.InspectorReflector;
import cn.donting.javelin.editor.inspector.InspectorValueType;
import cn.donting.javelin.editor.inspector.NodeInspectorVo;
import cn.donting.javelin.editor.inspector.RotationMath;
import cn.donting.javelin.editor.swing.SvgIcon;
import cn.donting.javelin.editor.swing.dialog.AddComponentDialog;
import cn.donting.javelin.editor.swing.inspector.widget.InsFormat;
import cn.donting.javelin.editor.window.MainFrame;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
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
    /** 旋转自旋回声抑制（v0.15）：近期写入环容量——覆盖 EDT 提交与引擎帧边界的滞后窗口 */
    private static final int ROTATION_WRITE_HISTORY = 8;
    /** 旋转自旋回声抑制：本面板近期写出的旋转（事件旋转与近期任一写入同旋转即跳过回写） */
    private final Quaternionf[] rotationWrites = new Quaternionf[ROTATION_WRITE_HISTORY];
    private int rotationWriteCursor;
    /** 新增组件待定位（添加成功后下一次重建滚到该分组并强制展开；重建完/换选中清空） */
    private Component pendingReveal;
    /** 「添加组件」底栏（常驻面板底部不随内容滚动；有节点时随滚动区一并挂/卸） */
    private JComponent addComponentBar;

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
        clearRotationWrites();
        pendingReveal = null;
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
            if (!belongsToUserScene(editor, node)) {
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
        ComponentSection revealTarget = null;
        for (ComponentInspectorVo componentVo : vo.components) {
            content.add(Box.createVerticalStrut(2));
            ComponentSection section = new ComponentSection(vo.node, componentVo, this::commitField,
                    componentVo.component != pendingReveal
                            && collapsedByName.getOrDefault(componentVo.name, false),
                    ComponentHeaderMenus.menuSupplier(componentVo, () -> onRemoveComponent(componentVo)));
            sections.add(section);
            content.add(section);
            if (componentVo.component == pendingReveal) {
                revealTarget = section;
            }
        }
        content.add(Box.createVerticalStrut(2));
        if (scrollPane == null) {
            remove(emptyLabel);
            scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            // 宽度恒跟视口（tracksWidth=true 兜底）：窄面板收缩/换行，不出水平滚动条
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            add(scrollPane, BorderLayout.CENTER);
            addComponentBar = buildAddComponentBar();
            add(addComponentBar, BorderLayout.SOUTH);
        } else {
            scrollPane.setViewportView(content);
        }
        revalidate();
        repaint();
        scrollToReveal(revealTarget);
    }

    /** 重建前记忆当前折叠态（重建后经构造参数恢复） */
    private void rememberCollapsed() {
        for (ComponentSection section : sections) {
            collapsedByName.put(section.getComponentName(), section.isCollapsed());
        }
    }

    /** 滚动到新增组件分组（布局延后一帧——revalidate 后 bounds 才为准） */
    private void scrollToReveal(ComponentSection section) {
        if (section == null) {
            return;
        }
        pendingReveal = null;
        SwingUtilities.invokeLater(() -> section.scrollRectToVisible(section.getBounds()));
    }

    /** 清空为内部空态（僵尸选中兜底；常态清空由外壳接管） */
    private void showEmpty() {
        selectedNode = null;
        nodeHeader = null;
        sections.clear();
        if (scrollPane != null) {
            remove(scrollPane);
            scrollPane = null;
            if (addComponentBar != null) {
                // 底栏随滚动区成对挂卸（空态不摆「添加组件」）
                remove(addComponentBar);
                addComponentBar = null;
            }
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
        if (InspectorValueType.ROTATION.name().equals(valueType)) {
            // 自旋回声记账（记账解析须与引擎 apply 同式：同 wire → 同四元数，判定才成立）
            recordRotationWrite(wireValue);
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

    // ------------------------------------------------------------------ 添加组件（Unity Add Component）

    /**
     * 「添加组件」底栏（面板 SOUTH 常驻不随内容滚动，2026-11-08 用户拍板）：1px 分隔线 +
     * 满宽按钮；唯一性冲突项在选择窗内灰显。
     */
    private JComponent buildAddComponentBar() {
        JButton button = new JButton(EditorLang.text("inspector.addComponent"), addIcon());
        button.addActionListener(e -> onAddComponent());
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setOpaque(false);
        bar.add(new JSeparator(), BorderLayout.NORTH);
        bar.add(button, BorderLayout.CENTER);
        return bar;
    }

    /** 「+」图标（add.svg；资源缺失不占位——菜单域同口径） */
    private static Icon addIcon() {
        SvgIcon icon = SvgIcon.of("add", 16);
        return icon.isAvailable() ? icon : null;
    }

    /** 点击入口：帧边界收集候选（EDT 不读节点组件表）→ EDT 弹选择窗 */
    private void onAddComponent() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过添加组件");
            return;
        }
        Node node = selectedNode;
        if (node == null) {
            return;
        }
        editor.submitTask(() -> {
            if (!belongsToUserScene(editor, node)) {
                return;
            }
            List<AddComponentVo> candidates = AddComponentCatalog.collect(node);
            SwingUtilities.invokeLater(() -> openAddComponentDialog(node, candidates));
        });
    }

    /** 弹选择窗（EDT）；选中则帧边界加组件（选中已前进则丢弃——候选面是旧节点的） */
    private void openAddComponentDialog(Node node, List<AddComponentVo> candidates) {
        if (selectedNode != node) {
            return;
        }
        Class<? extends Component> type = AddComponentDialog.pick(
                SwingUtilities.getWindowAncestor(this), candidates,
                EditorLang.text("inspector.addComponent"));
        if (type == null) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，丢弃添加组件");
            return;
        }
        editor.submitTask(() -> addComponent(editor, node, type));
    }

    /** 帧边界加组件（列表已滤冲突，异常=选中期间场景变化的竞态兜底——红线 15 弹错） */
    private void addComponent(JavelinEditor editor, Node node, Class<? extends Component> type) {
        if (!belongsToUserScene(editor, node)) {
            return;
        }
        Component added;
        try {
            added = node.addComponent(type);
            editor.getEditorScene().markDirty();
        } catch (IllegalStateException e) {
            String message = EditorLang.text("inspector.addComponent.failed", e.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainFrame.getInstance(),
                    message, EditorLang.text("inspector.addComponent"), JOptionPane.ERROR_MESSAGE));
            return;
        }
        LOGGER.info("添加组件: {} → {}", type.getName(), node.getName());
        SwingUtilities.invokeLater(() -> {
            pendingReveal = added;
            requestRefresh();
        });
    }

    /**
     * 归属校验（TransformWatchAppState 先例）：node.getScene() 挂的是 GameScene
     *（用户场景），getEditorScene() 是包装器——须比 getGameScene() 本体。
     */
    private static boolean belongsToUserScene(JavelinEditor editor, Node node) {
        GameScene userScene = editor.getEditorScene().getGameScene();
        return userScene != null && node.getScene() == userScene;
    }

    // ------------------------------------------------------------------ 组件头右键菜单（移除组件）

    /** 组件头「删除」入口：帧边界移除组件 + 置脏 + 面板自拉 */
    private void onRemoveComponent(ComponentInspectorVo vo) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过移除组件");
            return;
        }
        Node node = selectedNode;
        if (node == null) {
            return;
        }
        Component component = vo.component;
        editor.submitTask(() -> removeComponent(editor, node, component));
    }

    /** 帧边界移除组件（归属即时校验；二次移除/错宿主由 core 抛错——红线 15 弹错） */
    private void removeComponent(JavelinEditor editor, Node node, Component component) {
        if (!belongsToUserScene(editor, node)) {
            return;
        }
        try {
            node.removeComponent(component);
            editor.getEditorScene().markDirty();
        } catch (IllegalStateException e) {
            String message = EditorLang.text("inspector.removeComponent.failed", e.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainFrame.getInstance(),
                    message, EditorLang.text("inspector.removeComponent"), JOptionPane.ERROR_MESSAGE));
            return;
        }
        LOGGER.info("移除组件: {} ← {}", component.getClass().getName(), node.getName());
        SwingUtilities.invokeLater(this::requestRefresh);
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
        if (rotation != null && !isSelfRotationWrite(event.getRotation())) {
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

    /** 四元数 → 欧拉角度数串（RotationMath——收集侧 serialize 同口径） */
    private static String eulerWire(float[] quaternion) {
        Quaternionf q = new Quaternionf(quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
        float[] deg = RotationMath.toDegrees(q);
        return InsFormat.join(deg[0], deg[1], deg[2]);
    }

    // ------------------------------------------------------------------ 旋转自旋回声抑制（v0.15）

    /** 近期写入入环（环满覆盖最旧；非法串不入环——防御兜底） */
    private void recordRotationWrite(String wireValue) {
        float[] deg = InsFormat.split(wireValue, 3);
        if (deg == null) {
            return;
        }
        rotationWrites[rotationWriteCursor] = RotationMath.fromDegrees(deg[0], deg[1], deg[2]);
        rotationWriteCursor = (rotationWriteCursor + 1) % ROTATION_WRITE_HISTORY;
    }

    /**
     * 事件旋转是否为本面板近期写入——是则跳过回写（保留用户输入真值，规范翻转的
     * 等价表示不回灌显示；真外部变更照常刷新）。判定比事件原始四元数：与写入逐位
     * 同一，免欧拉往返在俯仰 ±90° 邻域的数值噪声。
     */
    private boolean isSelfRotationWrite(float[] quaternion) {
        Quaternionf q = new Quaternionf(quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
        for (Quaternionf write : rotationWrites) {
            if (write != null && RotationMath.sameRotation(write, q)) {
                return true;
            }
        }
        return false;
    }

    /** 清空近期写入环（选中切换——上一节点的写入不得参与本节点判定） */
    private void clearRotationWrites() {
        for (int i = 0; i < rotationWrites.length; i++) {
            rotationWrites[i] = null;
        }
        rotationWriteCursor = 0;
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
