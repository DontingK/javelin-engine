package com.javelin.editor.window;

import com.javelin.editor.project.GradleProject;
import com.javelin.editor.project.RecentProjectManager;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

/**
 * 项目管理器窗口（Swing 版）。
 *
 * <p>去 Web 化迁移（2026-10-17，Editor_Swing_Design §9 M6 项提前）：替换 CEF
 * projectManager 页——最近项目列表 / 新建项目 / 打开项目三模式功能面对齐。数据与
 * 操作走 Java 直调（{@link RecentProjectManager} / {@link GradleProject} /
 * tinyfd 目录选择），不再依赖 Vite dev server；原 /project/recentProjects、
 * /project/openProject、/project/createProject 三桥方法随消费方消亡同步退役
 * （§7.1 桥退役原则）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class ProjectFrame extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(ProjectFrame.class);

    /** 主区三模式卡片 id（对齐 web 页 list/create/open 三形态） */
    private static final String CARD_LIST = "list";
    private static final String CARD_CREATE = "create";
    private static final String CARD_OPEN = "open";
    /** 最近列表页中心区卡片 id：空提示 / 列表 */
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_ENTRIES = "entries";

    private final RecentProjectManager recentProjectManager = new RecentProjectManager();
    private final CardLayout cards = new CardLayout();
    /** 主区容器（list / create / open 三卡片） */
    private final JPanel cardPane = new JPanel(cards);
    private final JPanel listCenter = new JPanel(new CardLayout());
    private final DefaultListModel<File> recentModel = new DefaultListModel<>();
    /** 最近列表 hover 行下标（-1 = 无；渲染器据此高亮，web .project-card:hover 对齐） */
    private int hoverIndex = -1;
    private final JTextField nameField = new JTextField();
    private final JTextField createPathField = new JTextField();
    private final JTextField openPathField = new JTextField();

    public ProjectFrame() {
        setTitle("Project Manager - Javelin Editor");
        // 关窗 = 退出启动器（未开项目时 JVM 随最后一个窗口 dispose 终止）
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(buildSidebar(), BorderLayout.WEST);
        buildCards();
        getContentPane().add(cardPane, BorderLayout.CENTER);
        refreshRecent();
        cards.show(cardPane, CARD_LIST);
    }

    // ---------- 界面装配 ----------

    /** 左侧栏：品牌 + 新建/打开入口 */
    private JComponent buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.PAGE_AXIS));
        sidebar.setBorder(new EmptyBorder(20, 16, 20, 16));
        sidebar.setPreferredSize(new Dimension(200, 0));
        JLabel brand = new JLabel("Javelin Engine");
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 16f));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(20));
        sidebar.add(sidebarButton("新建项目", this::startCreate));
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(sidebarButton("打开项目", this::startOpen));
        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }

    private JButton sidebarButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
        button.addActionListener(e -> action.run());
        return button;
    }

    /** 主区三卡片装配（最近列表 / 新建表单 / 打开表单） */
    private void buildCards() {
        cardPane.add(buildListCard(), CARD_LIST);
        cardPane.add(buildFormCard("新建项目", buildCreateForm()), CARD_CREATE);
        cardPane.add(buildFormCard("打开项目", buildOpenForm()), CARD_OPEN);
    }

    /** 最近项目列表卡片：标题 + 空提示/列表切换 + 单击打开（web 卡片单击口径） */
    private JComponent buildListCard() {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(24, 32, 24, 32));
        card.add(pageTitle("最近项目"), BorderLayout.NORTH);
        JList<File> recentList = new JList<>(recentModel);
        recentList.setCellRenderer(new ProjectCellRenderer());
        recentList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = recentList.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                Rectangle bounds = recentList.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(e.getPoint())) {
                    return;
                }
                openProject(recentModel.getElementAt(index));
            }
        });
        // hover 高亮 + 手型光标（locationToIndex 对空白区返回最近行，须 bounds 命中判定）
        recentList.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = recentList.locationToIndex(e.getPoint());
                Rectangle bounds = recentList.getCellBounds(index, index);
                boolean onCell = index >= 0 && bounds != null && bounds.contains(e.getPoint());
                recentList.setCursor(onCell ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
                int newHover = onCell ? index : -1;
                if (newHover != hoverIndex) {
                    hoverIndex = newHover;
                    recentList.repaint();
                }
            }
        });
        recentList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverIndex != -1) {
                    hoverIndex = -1;
                    recentList.repaint();
                }
            }
        });
        listCenter.add(new JScrollPane(recentList), CARD_ENTRIES);
        JLabel emptyTip = new JLabel("暂无最近项目，点击左侧\"新建项目\"或\"打开项目\"。");
        emptyTip.setForeground(UIManager.getColor("Label.disabledForeground"));
        listCenter.add(emptyTip, CARD_EMPTY);
        card.add(listCenter, BorderLayout.CENTER);
        return card;
    }

    /** 表单卡片骨架：标题 + 限定宽度（440）左对齐表单 */
    private JComponent buildFormCard(String title, JComponent form) {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(24, 32, 24, 32));
        card.add(pageTitle(title), BorderLayout.NORTH);
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.PAGE_AXIS));
        form.setMaximumSize(new Dimension(440, Integer.MAX_VALUE));
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(form);
        wrapper.add(Box.createVerticalGlue());
        card.add(wrapper, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildCreateForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.PAGE_AXIS));
        form.add(formRow("项目名称", nameField));
        form.add(Box.createVerticalStrut(12));
        form.add(formRow("项目路径", createPathField));
        form.add(Box.createVerticalStrut(20));
        JButton cancelButton = new JButton("取消");
        cancelButton.addActionListener(e -> cards.show(cardPane, CARD_LIST));
        JButton submitButton = new JButton("创建");
        submitButton.addActionListener(e -> submitCreate());
        form.add(actionsRow(cancelButton, submitButton));
        return form;
    }

    private JComponent buildOpenForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.PAGE_AXIS));
        form.add(formRow("项目路径", openPathField));
        form.add(Box.createVerticalStrut(20));
        JButton cancelButton = new JButton("取消");
        cancelButton.addActionListener(e -> cards.show(cardPane, CARD_LIST));
        JButton submitButton = new JButton("打开");
        submitButton.addActionListener(e -> submitOpen());
        form.add(actionsRow(cancelButton, submitButton));
        return form;
    }

    /** 表单行：label 在上、输入框占满行宽（web 页同形态） */
    private JComponent formRow(String label, JTextField field) {
        JPanel row = new JPanel(new BorderLayout(0, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel(label), BorderLayout.NORTH);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private JComponent actionsRow(JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private JLabel pageTitle(String text) {
        JLabel title = new JLabel(text);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        return title;
    }

    // ---------- 业务流 ----------

    /** 新建项目：先 tinyfd 选父目录 → 进创建表单（路径预填、名称待填） */
    private void startCreate() {
        String directory = TinyFileDialogs.tinyfd_selectFolderDialog("选择目标文件夹", null);
        if (directory == null || directory.isBlank()) {
            return;
        }
        nameField.setText("");
        createPathField.setText(directory);
        cards.show(cardPane, CARD_CREATE);
    }

    /** 打开项目入口：清空路径进打开表单（最近列表是主路径，此处手动输入兜底） */
    private void startOpen() {
        openPathField.setText("");
        cards.show(cardPane, CARD_OPEN);
    }

    private void submitCreate() {
        String name = nameField.getText().trim();
        String directory = createPathField.getText().trim();
        if (name.isEmpty() || directory.isEmpty()) {
            return;
        }
        try {
            GradleProject project = GradleProject.createNewProject(directory, name, "com.javelin", name);
            openProject(project.getRootDir());
        } catch (Exception ex) {
            log.error("创建项目失败: {}", ex.getMessage(), ex);
            JOptionPane.showMessageDialog(this, "创建项目失败: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void submitOpen() {
        String path = openPathField.getText().trim();
        if (path.isEmpty()) {
            return;
        }
        openProject(new File(path));
    }

    /**
     * 打开项目（最近列表 / 打开表单 / 新建成功共用出口）：入最近列表 → 关本窗 →
     * 换 {@link ProjectLoadingFrame}（资产导入 + 引擎启动在 Engine Main 线程）。
     */
    private void openProject(File projectDir) {
        if (!projectDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "项目目录不存在: " + projectDir.getAbsolutePath(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        recentProjectManager.addProject(projectDir.getAbsolutePath());
        dispose();
        ProjectLoadingFrame loadingFrame = new ProjectLoadingFrame(projectDir);
        loadingFrame.setVisible(true);
    }

    /** 最近列表刷新（RecentProjectManager 读侧已过滤不存在项），空态切提示卡片 */
    private void refreshRecent() {
        recentModel.clear();
        for (String path : recentProjectManager.getRecentProjects()) {
            recentModel.addElement(new File(path));
        }
        ((CardLayout) listCenter.getLayout())
                .show(listCenter, recentModel.isEmpty() ? CARD_EMPTY : CARD_ENTRIES);
    }

    /**
     * hover 背景 = 列表底色向前景色轻混（FlatLaf darken 派生同思路，随主题走，
     * 无硬编码色值——LAF 未提供 List.hoverBackground key，JList 无内建 hover）。
     */
    private static Color hoverColor(JList<?> list) {
        Color bg = list.getBackground();
        Color fg = list.getForeground();
        float ratio = 0.08f;
        return new Color(
                Math.round(bg.getRed() + (fg.getRed() - bg.getRed()) * ratio),
                Math.round(bg.getGreen() + (fg.getGreen() - bg.getGreen()) * ratio),
                Math.round(bg.getBlue() + (fg.getBlue() - bg.getBlue()) * ratio));
    }

    /** 最近项目两行单元格：项目名（主色）+ 路径（disabledForeground 次色），无硬编码色值 */
    private class ProjectCellRenderer extends JPanel implements ListCellRenderer<File> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel pathLabel = new JLabel();

        ProjectCellRenderer() {
            setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
            setBorder(new EmptyBorder(10, 12, 10, 12));
            setOpaque(true);
            add(nameLabel);
            add(pathLabel);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends File> list, File value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            nameLabel.setText(value.getName());
            pathLabel.setText(value.getAbsolutePath());
            boolean hover = index == hoverIndex && !isSelected;
            setBackground(isSelected ? list.getSelectionBackground()
                    : hover ? hoverColor(list) : list.getBackground());
            nameLabel.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            pathLabel.setForeground(isSelected ? list.getSelectionForeground()
                    : UIManager.getColor("Label.disabledForeground"));
            return this;
        }
    }
}
