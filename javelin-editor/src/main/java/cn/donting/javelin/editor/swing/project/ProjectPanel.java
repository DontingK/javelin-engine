package cn.donting.javelin.editor.swing.project;

import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.ProjectChangedEvent;
import cn.donting.javelin.editor.file.FileType;
import cn.donting.javelin.editor.file.FileTypeRegistry;
import cn.donting.javelin.editor.menu.MenuPopupFactory;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.selection.EditorSelection;
import cn.donting.javelin.editor.swing.SvgIcon;
import cn.donting.javelin.editor.swing.panel.EditorPanel;
import cn.donting.javelin.editor.util.ThreadUtil;
import cn.donting.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.TransferHandler;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目面板（Unity Project 窗口布局——左右分栏，Editor_Swing_Design §7.2：原 web
 * ProjectPanel 的 Swing 实装，M3）。
 *
 * <p><b>布局</b>：左树仅目录（全量树目录投影，隐藏根挂 Assets/src 两根行）+ 右区
 * 显示选中目录直接子项（目录优先+名称序，快照侧已排）+ 面包屑逐级跳转 + 右区目录
 * 双击进入（等同左树选中）；分栏 {@link JSplitPane} 可拖拽（连续实时布局，面板内
 * 无画布用默认驱动即可——DockSplitPane 自研拖拽仅因 heavyweight 画布断链，见其
 * javadoc）。左树选中即右区内容源锚点：单一状态、无平行副本。</p>
 *
 * <p><b>数据通道（桥退役、Java 直调，§7.3 线程口径）</b>：项目文件树是纯 IO、不涉
 * 引擎状态——不走 submitTask 帧队列，{@link ProjectTreeSnapshot#build} 后台线程
 * 组装、EDT 应用。构造期首拉 + {@code project.changed} 事件重拉（首个改盘命令
 * project.create.material 落地时接线，2026-10-19，恢复当前目录锚点不丢位置）。</p>
 *
 * <p><b>选中单源</b>：所有选中（左树目录/右区条目/面包屑导航/双击进入）直写
 * {@link EditorSelection} setSelectedPath（Node 直持 File，节点由 rootDir 遍历
 * 产出天然不越项目根——原桥「相对路径上报 + 解析闸门」环节随直调消亡）。右键三
 * 入口先显式落选中再弹 ctx.project 域菜单（{@link MenuPopupFactory} 统一入口，
 * 菜单 enabled 求值读 EditorSelection 防旧态）：左树目录行 / 右区条目 / 右区空白
 * （=当前目录语境）；左树空白右键忽略（无语境源，web 等价口径）。与 web 版的一处
 * 收窄：面包屑导航在 web 版只切目录不重报选中，Swing 统一为「目录变更即上报」——
 * EditorSelection 与面板所见恒一致。</p>
 *
 * <p><b>图标（IDEA 项目视图口径）</b>：Assets=resourcesRoot、src 与
 * src/main/java=sourceRoot、src/main/java 子目录=package、其余目录=folder、
 * .java=class、.json=json、未知类型=unknown。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class ProjectPanel extends JComponent implements EditorPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectPanel.class);

    /** 右键菜单域（EditorMenuSetup 注册的项目面板右键域） */
    private static final String MENU_DOMAIN = "ctx.project";
    /** 节点图标尺寸（px，原 web project-node-icon 口径） */
    private static final int ICON_SIZE = 14;
    /** 左树初始宽度（px，原 web SplitPanel initial-size 口径） */
    private static final int INITIAL_LEFT_WIDTH = 180;
    /** 左树最小宽度（px，原 web SplitPanel min-size 口径——分割条左界钳制源） */
    private static final int MIN_LEFT_WIDTH = 100;
    /** 右区卡片：条目列表 / 空目录占位 */
    private static final String CARD_LIST = "list";
    private static final String CARD_EMPTY = "empty";

    /** 图标缓存（名 → 14px 实例；SvgIcon 内部缓存解析文档，此处省渲染期对象垃圾。null=缺资源，映射保留防重复告警） */
    private static final Map<String, SvgIcon> ICONS = new HashMap<>();

    private final JTree dirTree;
    private final DefaultTreeModel treeModel;
    private final JPanel breadcrumb;
    private final JList<ProjectTreeSnapshot.Node> entryList;
    private final DefaultListModel<ProjectTreeSnapshot.Node> entryModel;
    private final JPanel rightContent;
    private final JSplitPane splitPane;
    private final JLabel loadingLabel;

    /** relativePath → 左树行节点（面包屑/双击进入的程序化选中索引） */
    private final Map<String, DefaultMutableTreeNode> dirRowIndex = new HashMap<>();
    /** 快照根层（Assets/src；默认选中裁决数据源） */
    private Iterable<ProjectTreeSnapshot.Node> roots = List.of();
    /** 当前目录（左树选中=右区内容源锚点；树未挂载前 null） */
    private ProjectTreeSnapshot.Node selectedDir;
    /** 当前目录路径记忆（快照重拉后恢复选中锚点用；onDirSelected 持续更新） */
    private String lastSelectedDirPath;
    /** 条目上按下未决（压制选中监听上报；是否成拖拽未知，提交留待 release） */
    private boolean mousePressActive;
    /** 已成拖拽（release 被吞不上报；exportDone 复位） */
    private boolean dragOccurred;
    /** 分栏已挂载（首个快照后 loading→分栏一次性切换） */
    private boolean splitMounted;
    /** 首次定分栏位标志（构造期尺寸为 0，setDividerLocation 不生效，首个 resize 落位） */
    private boolean dividerApplied;

    public ProjectPanel() {
        setLayout(new BorderLayout(0, 0));
        loadingLabel = new JLabel(EditorLang.text("project.panel.loading"), JLabel.CENTER);
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            loadingLabel.setForeground(dim);
        }
        add(loadingLabel, BorderLayout.CENTER);

        // ------------------------------------------------------------------ 左树（仅目录）
        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
        dirTree = new JTree(treeModel);
        dirTree.setRootVisible(false);
        dirTree.setShowsRootHandles(true);
        dirTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        dirTree.setCellRenderer(new DirRenderer());
        dirTree.addTreeSelectionListener(e -> {
            // 选中被清（换树/无选中）不动当前目录锚点——右区内容保持，无清空语义
            TreePath path = dirTree.getSelectionPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof ProjectTreeSnapshot.Node dir) {
                onDirSelected(dir);
            }
        });
        dirTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleTreePopup(e);
                    return;
                }
                // 行带左键点=上报选中（同目录重点选无选中变更事件，显式重报对齐
                // web「点击即上报」；BasicTreeUI 原生选中由默认行为负责）
                if (SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = pathInRowBand(e.getX(), e.getY());
                    if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                            && node.getUserObject() instanceof ProjectTreeSnapshot.Node dir) {
                        EditorSelection.setSelectedPath(dir.getFile());
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleTreePopup(e);
                }
            }
        });

        JScrollPane treeScroll = new JScrollPane(dirTree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeScroll.setMinimumSize(new Dimension(MIN_LEFT_WIDTH, 0));

        // ------------------------------------------------------------------ 右区（面包屑 + 条目列表）
        breadcrumb = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        breadcrumb.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()));

        entryModel = new DefaultListModel<>();
        entryList = new JList<>(entryModel);
        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entryList.setCellRenderer(new EntryRenderer());
        entryList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            // 拖拽≠选中（Unity 口径）。拖拽启用后 JDK BasicListUI 按下即在
            // mousePressed 派发内改选中且 adjusting=false（dragEnabled 路径不设
            // adjusting——源码实锤），事件先于本面板 mousePressed 监听到达、压制
            // 标记尚未立起——故上报 invokeLater 推迟一拍，待 mousePressed 立标记
            // 后再裁决（拖/按中不上报；键盘路径无 mouse 事件照常上报）。空选中
            // 不上报（换目录换模型/空白清除，均无清空语义——web 等价口径）
            SwingUtilities.invokeLater(() -> {
                if (mousePressActive || dragOccurred) {
                    return;
                }
                ProjectTreeSnapshot.Node node = entryList.getSelectedValue();
                if (node != null) {
                    EditorSelection.setSelectedPath(node.getFile());
                }
            });
        });
        // 拖拽源（AssetInput 消费方，.mat 联动第一项 2026-10-25）：右区条目拖出
        // javaFileListFlavor 的 File（仅文件，目录无拖拽语义）
        entryList.setDragEnabled(true);
        entryList.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                ProjectTreeSnapshot.Node node = entryList.getSelectedValue();
                if (node == null || node.isDirectory()) {
                    return null;
                }
                dragOccurred = true;
                File file = node.getFile();
                return new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[]{DataFlavor.javaFileListFlavor};
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return DataFlavor.javaFileListFlavor.equals(flavor);
                    }

                    @Override
                    public Object getTransferData(DataFlavor flavor) {
                        return List.of(file);
                    }
                };
            }

            @Override
            protected void exportDone(JComponent c, Transferable data, int action) {
                // 拖拽结束（落点/取消皆达）：复位拖拽压制标记（release 事件已被
                // DnD 吞掉不会走到 mouseReleased 复位路径）
                dragOccurred = false;
                mousePressActive = false;
            }
        });
        entryList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleListPopup(e);
                    return;
                }
                // 空白点选清高亮（Unity 口径；不上报——不清单源选中）
                if (SwingUtilities.isLeftMouseButton(e) && entryRowAt(e) < 0) {
                    entryList.clearSelection();
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    // 条目上按下：压制选中监听上报（是否成拖拽未知，提交留待 release）
                    mousePressActive = true;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleListPopup(e);
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && mousePressActive && !dragOccurred) {
                    // 未成拖拽的完整点击 = 选中提交（拖拽路径 release 被吞、不上报）
                    ProjectTreeSnapshot.Node node = entryList.getSelectedValue();
                    if (node != null) {
                        EditorSelection.setSelectedPath(node.getFile());
                    }
                }
                mousePressActive = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // 目录双击进入（等同左树选中该目录）；文件双击分发注册表打开
                //（不可打开类型无动作，IconFileType canOpen=false）
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int index = entryRowAt(e);
                    if (index >= 0) {
                        ProjectTreeSnapshot.Node entry = entryModel.get(index);
                        if (entry.isDirectory()) {
                            selectDirByPath(entry.getRelativePath());
                        } else {
                            openEntry(entry);
                        }
                    }
                }
            }
        });

        JScrollPane listScroll = new JScrollPane(entryList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        JLabel emptyLabel = new JLabel(EditorLang.text("project.panel.emptyDir"), JLabel.CENTER);
        if (dim != null) {
            emptyLabel.setForeground(dim);
        }
        rightContent = new JPanel(new CardLayout());
        rightContent.add(listScroll, CARD_LIST);
        rightContent.add(emptyLabel, CARD_EMPTY);

        JPanel rightPanel = new JPanel(new BorderLayout(0, 0));
        rightPanel.add(breadcrumb, BorderLayout.NORTH);
        rightPanel.add(rightContent, BorderLayout.CENTER);

        // ------------------------------------------------------------------ 分栏（连续实时布局；右侧吸收增量，左树定宽）
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, rightPanel);
        splitPane.setBorder(null);
        splitPane.setContinuousLayout(true);
        splitPane.setResizeWeight(1.0);
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!dividerApplied && splitPane.getWidth() > 0) {
                    dividerApplied = true;
                    splitPane.setDividerLocation(INITIAL_LEFT_WIDTH);
                }
            }
        });

        requestSnapshot();

        // 盘面变更刷新链（project.changed，随首个改盘命令落地接线）：重拉快照
        // 并恢复当前目录锚点——面板常驻不退订
        EditorEventBus.subscribe(ProjectChangedEvent.class,
                e -> SwingUtilities.invokeLater(this::requestSnapshot));
    }

    // ------------------------------------------------------------------ 数据通道

    /**
     * 请求项目文件树快照：后台线程纯 IO 组装 → EDT 应用（非阻塞）。构造期首拉 +
     * {@code project.changed} 事件重拉（盘面变更命令落地后接线，2026-10-19）；
     * 重拉经 {@link #lastSelectedDirPath} 恢复当前目录锚点，不丢用户位置。
     */
    private void requestSnapshot() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            // 防御分支：面板构造晚于引擎装配（instance 在 JavelinEditor 构造器置位，
            // MainFrame.create 更晚），理论不可达——对齐 HierarchyPanel 同款守卫
            LOGGER.warn("编辑器实例未创建，跳过项目文件树拉取");
            return;
        }
        Project project = editor.getProject();
        ThreadUtil.run("project-tree-loader", () -> {
            ProjectTreeSnapshot snapshot = ProjectTreeSnapshot.build(project);
            SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
        });
    }

    /** 换树（EDT）：建左树行 + 展开隐藏根（两根行可见）+ 挂分栏 + 默认选中兜底 */
    private void applySnapshot(ProjectTreeSnapshot snapshot) {
        if (!snapshot.isReady()) {
            LOGGER.warn("项目文件树未就绪（project 缺席），项目面板停留加载态");
            return;
        }
        roots = snapshot.getRoots();
        dirRowIndex.clear();
        DefaultMutableTreeNode hiddenRoot = new DefaultMutableTreeNode();
        for (ProjectTreeSnapshot.Node root : roots) {
            hiddenRoot.add(buildDirRow(root));
        }
        treeModel.setRoot(hiddenRoot);
        dirTree.expandPath(new TreePath(hiddenRoot.getPath()));
        if (!splitMounted) {
            remove(loadingLabel);
            add(splitPane, BorderLayout.CENTER);
            splitMounted = true;
            revalidate();
            repaint();
        }
        // 选中锚点恢复：快照重拉优先回原目录（刷新链不丢用户位置，首拉无记忆走兜底）
        if (lastSelectedDirPath == null || dirRowIndex.get(lastSelectedDirPath) == null) {
            ensureDefaultSelection();
        } else {
            selectDirByPath(lastSelectedDirPath);
        }
    }

    /** 目录子树 → 左树行（仅目录投影：文件不出左树，右区走快照原始 children） */
    private DefaultMutableTreeNode buildDirRow(ProjectTreeSnapshot.Node dir) {
        DefaultMutableTreeNode row = new DefaultMutableTreeNode(dir);
        dirRowIndex.put(dir.getRelativePath(), row);
        for (ProjectTreeSnapshot.Node child : dir.getChildren()) {
            if (child.isDirectory()) {
                row.add(buildDirRow(child));
            }
        }
        return row;
    }

    /** 选中兜底：无效取 Assets（缺失取首根，面板不留空锚点——web 等价口径） */
    private void ensureDefaultSelection() {
        ProjectTreeSnapshot.Node target = null;
        for (ProjectTreeSnapshot.Node root : roots) {
            if ("Assets".equals(root.getRelativePath())) {
                target = root;
                break;
            }
        }
        if (target == null) {
            for (ProjectTreeSnapshot.Node root : roots) {
                target = root;
                break;
            }
        }
        if (target != null) {
            selectDirByPath(target.getRelativePath());
        }
    }

    // ------------------------------------------------------------------ 目录切换（唯一锚点收口）

    /**
     * 当前目录切换（左树选中/面包屑/双击进入共用收口）：切右区内容 + 重建面包屑 +
     * 上报 {@link EditorSelection}（目录变更即上报——含面包屑导航，见类 javadoc）。
     * 由左树选中变更事件驱动（程序化选中走 {@link #selectDirByPath} 落选中触发）。
     */
    private void onDirSelected(ProjectTreeSnapshot.Node dir) {
        selectedDir = dir;
        lastSelectedDirPath = dir.getRelativePath();
        refreshEntries(dir);
        rebuildBreadcrumb(dir);
        EditorSelection.setSelectedPath(dir.getFile());
    }

    /** 程序化选中目录（面包屑段点击/右区双击进入/默认选中兜底）：展开父链+滚动可见+落选中（事件驱动收口） */
    private void selectDirByPath(String relativePath) {
        DefaultMutableTreeNode row = dirRowIndex.get(relativePath);
        if (row == null) {
            return;
        }
        TreePath path = new TreePath(row.getPath());
        dirTree.scrollPathToVisible(path);
        dirTree.setSelectionPath(path);
    }

    /** 右区条目刷新：选中目录直接子项（快照侧已目录优先+名称序）；空目录切占位卡 */
    private void refreshEntries(ProjectTreeSnapshot.Node dir) {
        entryModel.clear();
        for (ProjectTreeSnapshot.Node child : dir.getChildren()) {
            entryModel.addElement(child);
        }
        ((CardLayout) rightContent.getLayout())
                .show(rightContent, entryModel.isEmpty() ? CARD_EMPTY : CARD_LIST);
    }

    /** 面包屑重建（根 → 当前目录逐级；段点击跳转对应目录） */
    private void rebuildBreadcrumb(ProjectTreeSnapshot.Node dir) {
        breadcrumb.removeAll();
        String[] segments = dir.getRelativePath().split("/");
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                breadcrumb.add(new JLabel("/"));
            }
            acc.append(segments[i]);
            breadcrumb.add(breadcrumbButton(segments[i], acc.toString()));
            acc.append('/');
        }
        breadcrumb.revalidate();
        breadcrumb.repaint();
    }

    /** 面包屑段按钮（文本样式无描边：贴 Unity 可点文本口径） */
    private JButton breadcrumbButton(String name, String relativePath) {
        JButton button = new JButton(name);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 2, 0, 2));
        button.addActionListener(e -> selectDirByPath(relativePath));
        return button;
    }

    // ------------------------------------------------------------------ 右键域菜单（ctx.project 三入口）

    /** 左树右键：行带命中先落选中再弹菜单；空白忽略（无语境源，web 等价口径） */
    private void handleTreePopup(MouseEvent e) {
        TreePath path = pathInRowBand(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        dirTree.setSelectionPath(path);
        if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof ProjectTreeSnapshot.Node dir) {
            reportAndPopup(dir, e);
        }
    }

    /**
     * 右区右键：条目行=落条目选中再弹菜单；空白=当前目录语境（先上报目录再弹——
     * 菜单 enabled 求值读 EditorSelection，须先落上报防读旧态）。
     */
    private void handleListPopup(MouseEvent e) {
        int index = entryRowAt(e);
        if (index >= 0) {
            entryList.setSelectedIndex(index);
            reportAndPopup(entryModel.get(index), e);
        } else if (selectedDir != null) {
            reportAndPopup(selectedDir, e);
        }
    }

    /** 显式落选中后弹 ctx.project 域菜单（统一入口，contentPane 坐标——同层级面板口径） */
    private void reportAndPopup(ProjectTreeSnapshot.Node node, MouseEvent e) {
        EditorSelection.setSelectedPath(node.getFile());
        MainFrame frame = MainFrame.getInstance();
        if (frame == null) {
            return;
        }
        Point location = SwingUtilities.convertPoint(
                (Component) e.getSource(), e.getX(), e.getY(), frame.getContentPane());
        MenuPopupFactory.show(MENU_DOMAIN, location.x, location.y);
    }

    // ------------------------------------------------------------------ 命中判定

    /**
     * 右区条目行命中（y 落在行高内返回行号，x 不限——JList locationToIndex 对空白
     * 也回最近行，须 bounds 复核；全部行之外 -1）。
     */
    private int entryRowAt(MouseEvent e) {
        int index = entryList.locationToIndex(e.getPoint());
        if (index < 0) {
            return -1;
        }
        Rectangle bounds = entryList.getCellBounds(index, index);
        return bounds != null && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height
                ? index : -1;
    }

    /**
     * 左树行带命中（y 落在某行高度内返回该行，x 不限；层级面板同款——展开手柄/
     * 行右空白均算行带，Unity 行带口径）。
     */
    private TreePath pathInRowBand(int x, int y) {
        TreePath closest = dirTree.getClosestPathForLocation(x, y);
        if (closest == null) {
            return null;
        }
        Rectangle bounds = dirTree.getPathBounds(closest);
        return bounds != null && y >= bounds.y && y < bounds.y + bounds.height
                ? closest : null;
    }

    // ------------------------------------------------------------------ 图标

    /** 图标取用（缓存；缺资源 null 不占空位，HierarchyPanel 同口径） */
    private static SvgIcon icon(String name) {
        if (!ICONS.containsKey(name)) {
            SvgIcon candidate = SvgIcon.of(name, ICON_SIZE);
            ICONS.put(name, candidate.isAvailable() ? candidate : null);
        }
        return ICONS.get(name);
    }

    /** 目录图标裁决：Assets=resourcesRoot、src 与 src/main/java=sourceRoot、src/main/java 子目录=package、其余=folder */
    private static SvgIcon dirIcon(ProjectTreeSnapshot.Node dir) {
        String path = dir.getRelativePath();
        if ("Assets".equals(path)) {
            return icon("resourcesRoot");
        }
        if ("src".equals(path) || "src/main/java".equals(path)) {
            return icon("sourceRoot");
        }
        if (path.startsWith("src/main/java/")) {
            return icon("package");
        }
        return icon("folder");
    }

    /** 条目图标裁决：目录走路径规则，文件查 FileTypeRegistry（未注册类型=unknown） */
    private static SvgIcon entryIcon(ProjectTreeSnapshot.Node node) {
        if (node.isDirectory()) {
            return dirIcon(node);
        }
        FileType type = FileTypeRegistry.find(node.getName());
        return icon(type != null ? type.icon() : "unknown");
    }

    /** 文件双击打开：分发 FileTypeRegistry（可打开类型执行 open，其余无动作） */
    private static void openEntry(ProjectTreeSnapshot.Node entry) {
        FileType type = FileTypeRegistry.find(entry.getName());
        if (type != null && type.canOpen()) {
            type.open(entry.getFile());
        }
    }

    /** 分隔线色（UIManager 单点收口，SceneViewToolbar 同款键序） */
    private static Color borderColor() {
        Color color = UIManager.getColor("Component.borderColor");
        if (color != null) {
            return color;
        }
        Color fallback = UIManager.getColor("Separator.foreground");
        return fallback != null ? fallback : Color.GRAY;
    }

    // ------------------------------------------------------------------ 渲染

    /** 左树行渲染：目录名 + 按相对路径裁决的目录图标 */
    private static final class DirRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            Object userObject = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (userObject instanceof ProjectTreeSnapshot.Node dir) {
                setIcon(dirIcon(dir));
                setText(dir.getName());
            } else {
                // 隐藏根不渲染（rootVisible=false，防御分支）
                setIcon(null);
                setText("");
            }
            return this;
        }
    }

    /** 右区条目渲染：图标 + 名称（选中高亮走 LAF 默认——FlatLaf accent 口径） */
    private static final class EntryRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof ProjectTreeSnapshot.Node node) {
                setIcon(entryIcon(node));
                setText(node.getName());
            }
            return this;
        }
    }
}
