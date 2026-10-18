package com.javelin.editor.swing.hierarchy;

import com.javelin.editor.JavelinEditor;
import com.javelin.core.scene.Node;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.NodeRenamedEvent;
import com.javelin.editor.event.SceneChangedEvent;
import com.javelin.editor.event.SceneDirtyEvent;
import com.javelin.editor.event.SceneSelectEvent;
import com.javelin.editor.menu.MenuPopupFactory;
import com.javelin.editor.selection.EditorSelection;
import com.javelin.editor.swing.SvgIcon;
import com.javelin.editor.swing.panel.EditorPanel;
import com.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 层级面板（左侧场景树，Editor_Swing_Design §7.2——原 web HierarchyPanel 的 Swing
 * 实装，M2 首面板）。
 *
 * <p><b>数据通道（桥退役、Java 直调，§7.3 线程口径）</b>：树快照经
 * {@code JavelinEngine.submitTask} 提交引擎主线程帧边界组装（{@link SceneTreeSnapshot#build}），
 * EDT 整体换树——不走桥、不阻塞 EDT。刷新分工（边沿通知，状态以拉取为准）：
 * {@code scene.changed}（结构/图层标签变更，含保存后 clearDirty）→ 重拉整树；
 * {@code scene.dirty}（净变脏翻转沿）→ 本地镜像置星标不重拉；{@code scene.nodeRenamed}
 * → 定点改行文本。展开态按节点 id 跨换树恢复。</p>
 *
 * <p><b>选中单源</b>：树行点击（含右键即选中）写 {@link EditorSelection}
 * setSelectedNode（场景根选中=清 Node 选中，volatile 直写 EDT 安全——
 * 2026-10-18 全链引用化拍板：NodeSnap 持 Node 引用作回查键，EDT 不读其字段、
 * 显示数据走拷贝）；
 * 外源选中（创建命令自动选中、将来视口拾取）经 {@code scene.select} 事件反向对齐
 * 树高亮 + scrollPathToVisible（顺带展开父链——创建命令后新节点自动可见）。
 * 程序化同步期抑制回写，防事件回声。</p>
 *
 * <p><b>右键域菜单</b>：右键先落选中再弹 ctx.hierarchy 域 Swing 菜单
 * （{@link MenuPopupFactory} 统一入口，contentPane 坐标——菜单 enabled 求值读
 * EditorSelection，须先落上报防读旧态，Editor_Menu_Design 口径）。JTree 默认
 * 右键不改选中（BasicTreeUI.selectPathForEvent 仅左键分支）、点空白不清选中
 * ——本面板按 Unity 口径补齐：右键行带选中/空白清除、左键点空白清除。</p>
 *
 * <p><b>未就绪重试</b>：场景未就绪（ready=false，启动竞态/loadScene 失败）时
 * Swing Timer 0.5s 重试、上限 30 次（原 web 面板口径平移）。事件订阅在构造期
 * 一次落定、面板实例经 DockRoot 缓存常驻——无退订路径（编辑器生命周期同寿）；
 * 订阅回调在总线泵线程，一律 invokeLater 跳 EDT。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class HierarchyPanel extends JComponent implements EditorPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchyPanel.class);

    /** 场景未就绪重试周期（毫秒）——原 web 面板口径 */
    private static final int RETRY_DELAY_MILLIS = 500;
    /** 场景未就绪重试上限（次） */
    private static final int MAX_RETRY = 30;
    /** 节点图标尺寸（px，原 web hierarchy-node-icon 口径） */
    private static final int NODE_ICON_SIZE = 14;
    /** 右键菜单域（EditorMenuSetup 注册的场景树右键域） */
    private static final String MENU_DOMAIN = "ctx.hierarchy";
    /** 未就绪占位根标签（首快照前面板未显示树，仅防 null userObject） */
    private static final String PLACEHOLDER_ROOT = "…";

    private final JTreeWrap tree;
    private final DefaultTreeModel model;
    private final JLabel loadingLabel;
    /** Node 引用 → 树行节点（IdentityHashMap 引用同一性键；换树时重建，定点改名/选中对齐 O(1) 索引） */
    private final Map<Node, DefaultMutableTreeNode> nodeIndex = new IdentityHashMap<>();
    /** 节点图标（box.svg 单实例；资源缺失时 null 不占空位，MenuPopupFactory 同口径） */
    private final SvgIcon nodeIcon;

    /** 树已挂载（首个 ready 快照后 loading→tree 一次性切换） */
    private boolean treeMounted;
    /** 脏星标（scene.dirty 置位；快照携带值在重拉时覆盖熄灭） */
    private boolean dirty;
    /** 场景根显示名（快照携带；星标由渲染器按 live dirty 叠加） */
    private String sceneName = PLACEHOLDER_ROOT;
    /** 未就绪重试定时器（EDT 专属：scheduleRetry 仅在 EDT 创建/启动） */
    private Timer retryTimer;
    private int retryCount;
    /** 程序化选中同步期抑制选中监听回写（防 scene.select 事件回声） */
    private boolean suppressSelectionReport;

    public HierarchyPanel() {
        setLayout(new BorderLayout(0, 0));
        loadingLabel = new JLabel("场景加载中…", SwingConstants.CENTER);
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            loadingLabel.setForeground(dim);
        }
        add(loadingLabel, BorderLayout.CENTER);

        SvgIcon icon = SvgIcon.of("box", NODE_ICON_SIZE);
        nodeIcon = icon.isAvailable() ? icon : null;

        model = new DefaultTreeModel(new DefaultMutableTreeNode(PLACEHOLDER_ROOT));
        tree = new JTreeWrap(model);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new NodeRenderer());

        subscribeEvents();
        requestSnapshot();
    }

    // ------------------------------------------------------------------ 数据通道

    /**
     * 请求树快照：引擎主线程帧边界组装 → EDT 换树（非阻塞——桥时代为同步返回须
     * 2s await，Swing 直调后回传走 invokeLater）。构造期在 Engine Main 线程调用，
     * pendingTasks 于首帧消费（时序在 loadScene 之后，首拉即 ready）。
     */
    private void requestSnapshot() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过场景树拉取");
            return;
        }
        editor.submitTask(() -> {
            SceneTreeSnapshot snapshot = SceneTreeSnapshot.build(editor.getEditorScene());
            SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
        });
    }

    /** 换树（EDT）：展开态按引用恢复、选中从 EditorSelection 现值对齐、首个 ready 快照挂树 */
    private void applySnapshot(SceneTreeSnapshot snapshot) {
        if (!snapshot.isReady()) {
            scheduleRetry();
            return;
        }
        stopRetry();
        dirty = snapshot.isDirty();
        sceneName = snapshot.getSceneName();
        Set<Node> expandedNodes = collectExpandedNodes();
        DefaultMutableTreeNode rootNode = buildNodeTree(snapshot);
        // 换树全程抑制选中回写：setRoot 会清选中触发 valueChanged，不抑制则
        // 向单源误报 null（随后 restoreSelection 恢复高亮但单源已丢真值）
        suppressSelectionReport = true;
        try {
            model.setRoot(rootNode);
            tree.expandPath(new TreePath(rootNode.getPath()));
            restoreExpansion(expandedNodes);
        } finally {
            suppressSelectionReport = false;
        }
        restoreSelection();
        if (!treeMounted) {
            remove(loadingLabel);
            JScrollPane scrollPane = new JScrollPane(tree);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            add(scrollPane, BorderLayout.CENTER);
            treeMounted = true;
            revalidate();
            repaint();
        }
    }

    /** 快照 → 树行节点（根行 userObject=快照本体，渲染器据类型区分场景根/节点行） */
    private DefaultMutableTreeNode buildNodeTree(SceneTreeSnapshot snapshot) {
        nodeIndex.clear();
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(snapshot);
        for (SceneTreeSnapshot.NodeSnap nodeSnap : snapshot.getRoots()) {
            rootNode.add(buildNode(nodeSnap));
        }
        return rootNode;
    }

    private DefaultMutableTreeNode buildNode(SceneTreeSnapshot.NodeSnap nodeSnap) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(nodeSnap);
        nodeIndex.put(nodeSnap.getNode(), node);
        for (SceneTreeSnapshot.NodeSnap child : nodeSnap.getChildren()) {
            node.add(buildNode(child));
        }
        return node;
    }

    /** 换树前收集展开节点引用（场景根恒展开，不入集） */
    private Set<Node> collectExpandedNodes() {
        Set<Node> expandedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        if (treeMounted && model.getRoot() instanceof DefaultMutableTreeNode root) {
            Enumeration<?> enumeration = root.depthFirstEnumeration();
            while (enumeration.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) enumeration.nextElement();
                if (node.getUserObject() instanceof SceneTreeSnapshot.NodeSnap snap
                        && tree.isExpanded(new TreePath(node.getPath()))) {
                    expandedNodes.add(snap.getNode());
                }
            }
        }
        return expandedNodes;
    }

    /** 换树后按引用恢复展开态（节点已删则静默丢弃） */
    private void restoreExpansion(Set<Node> expandedNodes) {
        if (expandedNodes.isEmpty()) {
            return;
        }
        for (Map.Entry<Node, DefaultMutableTreeNode> entry : nodeIndex.entrySet()) {
            if (expandedNodes.contains(entry.getKey())) {
                tree.expandPath(new TreePath(entry.getValue().getPath()));
            }
        }
    }

    /** 选中对齐（换树收尾：EditorSelection 现值为准——快照组装到应用之间选中可能已前进） */
    private void restoreSelection() {
        syncSelection(EditorSelection.getSelectedNode());
    }

    // ------------------------------------------------------------------ 未就绪重试

    /** 未就绪重试调度（EDT：applySnapshot 专用，Swing Timer 不跨线程启动） */
    private void scheduleRetry() {
        if (retryTimer == null) {
            retryTimer = new Timer(RETRY_DELAY_MILLIS, e -> {
                if (retryCount >= MAX_RETRY) {
                    stopRetry();
                    LOGGER.warn("场景树拉取未就绪，重试达上限（{} 次）放弃", MAX_RETRY);
                    loadingLabel.setText("场景未就绪");
                    return;
                }
                retryCount++;
                requestSnapshot();
            });
        }
        retryTimer.restart();
    }

    private void stopRetry() {
        if (retryTimer != null) {
            retryTimer.stop();
        }
        retryCount = 0;
    }

    // ------------------------------------------------------------------ 事件订阅（总线泵线程 → EDT）

    private void subscribeEvents() {
        EditorEventBus.subscribe(SceneChangedEvent.class,
                e -> SwingUtilities.invokeLater(this::requestSnapshot));
        EditorEventBus.subscribe(SceneDirtyEvent.class, e -> SwingUtilities.invokeLater(() -> {
            dirty = true;
            if (treeMounted) {
                tree.repaint();
            }
        }));
        EditorEventBus.subscribe(NodeRenamedEvent.class, e -> SwingUtilities.invokeLater(
                () -> applyRename(e.getNode(), e.getNewName())));
        EditorEventBus.subscribe(SceneSelectEvent.class, e -> SwingUtilities.invokeLater(
                () -> syncSelection(e.getNode())));
    }

    /** 定点改名（树未就绪或节点已删则静默——结构变更会随之重拉整树兜底） */
    private void applyRename(Node node, String newName) {
        DefaultMutableTreeNode treeNode = nodeIndex.get(node);
        if (treeNode == null || !(treeNode.getUserObject() instanceof SceneTreeSnapshot.NodeSnap snap)) {
            return;
        }
        snap.setName(newName);
        model.nodeChanged(treeNode);
    }

    /** 选中对齐（外源 scene.select / 换树收尾共用）：高亮 + 滚动可见（顺带展开父链） */
    private void syncSelection(Node node) {
        if (!treeMounted) {
            return;
        }
        DefaultMutableTreeNode treeNode = node != null ? nodeIndex.get(node) : null;
        suppressSelectionReport = true;
        try {
            if (treeNode != null) {
                TreePath path = new TreePath(treeNode.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            } else {
                tree.clearSelection();
            }
        } finally {
            suppressSelectionReport = false;
        }
    }

    // ------------------------------------------------------------------ 树封装（选中上报 + 右键域菜单 + 空白点选）

    /**
     * JTree 封装：选中监听（收口上报 EditorSelection）+ 鼠标交互（右键即选中/弹
     * ctx.hierarchy 域菜单、左键点空白清除选中——JTree 默认均不做，Unity 口径补齐）。
     */
    private final class JTreeWrap extends JTree {

        private JTreeWrap(DefaultTreeModel model) {
            super(model);
            addTreeSelectionListener(selectionListener());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        showContextMenu(e);
                    } else if (SwingUtilities.isLeftMouseButton(e)
                            && pathInRowBand(e.getX(), e.getY()) == null) {
                        // 点空白（全部行之外）清除选中——监听器上报 null
                        clearSelection();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        showContextMenu(e);
                    }
                }
            });
        }

        private TreeSelectionListener selectionListener() {
            return e -> {
                if (suppressSelectionReport) {
                    return;
                }
                TreePath path = getSelectionPath();
                if (path == null) {
                    EditorSelection.setSelectedNode(null);
                    return;
                }
                // 场景根选中 = 清 Node 选中（根非 Node；Unity 场景头选中语义）
                EditorSelection.setSelectedNode(
                        path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                                && node.getUserObject() instanceof SceneTreeSnapshot.NodeSnap snap
                                ? snap.getNode() : null);
            };
        }

        /**
         * 右键：先落选中再弹菜单（菜单 enabled 求值读 EditorSelection——右键前必须
         * 上报防读旧态）。命中行带（含手柄/行右空白，Unity 行带口径）选中该行，
         * 空白清除选中；坐标转 contentPane 后走 MenuPopupFactory 统一入口。
         */
        private void showContextMenu(MouseEvent e) {
            TreePath path = pathInRowBand(e.getX(), e.getY());
            if (path != null) {
                setSelectionPath(path);
            } else {
                clearSelection();
            }
            MainFrame frame = MainFrame.getInstance();
            if (frame == null) {
                return;
            }
            Point location = SwingUtilities.convertPoint(tree, e.getX(), e.getY(),
                    frame.getContentPane());
            MenuPopupFactory.show(MENU_DOMAIN, location.x, location.y);
        }

        /**
         * 行带命中：y 落在某行高度内返回该行（x 不限——右键口径；展开手柄点击
         * BasicTreeUI 只切换不选中，左键路径不命中即空白处理）。全部行之外 null。
         */
        private TreePath pathInRowBand(int x, int y) {
            TreePath closest = getClosestPathForLocation(x, y);
            if (closest == null) {
                return null;
            }
            Rectangle bounds = getPathBounds(closest);
            return bounds != null && y >= bounds.y && y < bounds.y + bounds.height
                    ? closest : null;
        }
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 树行渲染：全行 box 图标；场景根 = 场景名 + 脏星标（live dirty，scene.dirty
     * 置位 repaint 即时亮）；节点行 = 名称（快照兜底 GameObject）；inactive 灰显
     * （自身激活态——SceneWatch 尚未检测 active 翻转，随灰显落地再补快照字段增量）。
     */
    private final class NodeRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIcon(nodeIcon);
            Object userObject = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (userObject instanceof SceneTreeSnapshot.NodeSnap snap) {
                setText(snap.getName());
                if (!snap.isActive() && !selected) {
                    Color dim = UIManager.getColor("Label.disabledForeground");
                    if (dim != null) {
                        setForeground(dim);
                    }
                }
            } else {
                setText(sceneName + (dirty ? " *" : ""));
            }
            return this;
        }
    }
}
