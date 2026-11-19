package cn.donting.javelin.editor.swing.settings;

import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.SettingManager;
import cn.donting.javelin.core.scene.LayerAndTagManger;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.inspector.InspectorMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.settings.SettingsSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目设置窗口（对标 Unity {@code Edit → Project Settings}）。
 *
 * <p><b>非模态</b>（可停留、边调边看视口）；左栏分类导航 + 右栏内容（{@code ProjectPanel}
 * 分栏先例）。**页清单与页面内容全由设置对象派生**——{@link SettingsSchema#pagesOf} 扫
 * {@code JavelinSetting} 里类型贴 {@code @Setting} 的字段（声明序即导航序），每页按
 * {@link SettingsSchema} 展开成「分组 → 行」（行控件复用检查器叶子件）。加一页 = 加一个
 * {@code @Setting} 设置类 + 设置根上的字段，本类不改。</p>
 *
 * <p>改完<b>即时生效</b>：行改动写回设置实例后经 {@link JavelinEditor#applyRenderSettings()}
 * 单点下发——**仅对 {@code editorStartup = true} 的页成立**；{@code editorStartup = false} 的页
 * （窗口）宿主是项目文件那份，改了不碰编辑器活实例，只影响打包产物。关窗时逐页按
 * {@link SettingsSchema#managedRoots()} 做 load-modify-save 写盘到项目
 * {@code Game/javelinSettings.settings}（编辑器下次启动按 {@code editorStartup} 读回，见
 * {@code ProjectLoadingFrame.loadEditorSetting}）。</p>
 *
 * @author javelin
 * @create 2026/11/14
 */
public final class ProjectSettingsWindow extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSettingsWindow.class);

    /** 当前设置窗（单实例——再点替换旧窗；仅 EDT 访问） */
    private static ProjectSettingsWindow current;

    /** 页清单（页根宿主 / 声明 / 持久化字段清单都由它派生） */
    private final List<SettingsSchema> pages;

    /** 项目文件设置实例（只写不读的页以此为宿主，如窗口页） */
    private final JavelinSetting projectSetting;

    /** 各页当前展示的 schema（恢复默认后按页重建刷新，持久化仍走 {@link #pages}） */
    private final List<SettingsSchema> displayPages = new ArrayList<>();

    /** 各页卡片视图（恢复默认后原位替换） */
    private final List<JComponent> cardViews = new ArrayList<>();

    /** 卡片容器（按页切 CardLayout） */
    private JPanel cards;

    /**
     * 打开设置窗（EDT 调用）——镜像已有窗口则先关掉重建（保证回显最新值）。
     *
     * @param owner 宿主窗口（可为 null）
     */
    public static void show(Window owner) {
        if (JavelinEditor.getInstance() == null) {
            // 无引擎设置活实例即无可设置对象——显性报错，不拿哑实例装样子（红线 15）
            JOptionPane.showMessageDialog(owner, EditorLang.text("projectSettings.editorMissing"),
                    EditorLang.text("projectSettings.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (current != null && current.isDisplayable()) {
            current.dispose();
        }
        current = new ProjectSettingsWindow(owner);
        current.setVisible(true);
    }

    private ProjectSettingsWindow(Window owner) {
        super(owner, EditorLang.text("projectSettings.title"), ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.projectSetting = loadProjectSetting();
        // 页绑定分流：图形页绑编辑器活实例（改即生效），窗口页绑项目文件那份（只供打包）
        this.pages = SettingsSchema.pagesOf(liveSetting(), projectSetting);
        buildContent();
        setSize(560, 420);
        setMinimumSize(new Dimension(420, 300));
        setLocationRelativeTo(owner);
        // 写盘收口在关窗（改动时已即时生效；避免数字框键入即提交 / 拖动调值逐次落盘）
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                persist();
            }
        });
    }

    /**
     * 读项目设置文件（{@code @Setting(editorStartup = false)} 的页以此为宿主）：这类页显示并
     * 编辑的是**打包配置**，改了什么都不影响编辑器活实例。文件缺失即默认值；损坏显性报错后
     * 仍以默认值开窗（不阻断设置窗，红线 15）。
     */
    private static JavelinSetting loadProjectSetting() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            throw new IllegalStateException(EditorLang.text("projectSettings.editorMissing"));
        }
        File file = new File(editor.getProject().getGameDir(), SettingManager.JAVELIN_SETTINGS_FILE);
        if (!file.exists()) {
            return new JavelinSetting();
        }
        try {
            return new BinaryImporter(file).importData(JavelinSetting.class);
        } catch (Exception e) {
            LOGGER.error("项目设置读取失败", e);
            JOptionPane.showMessageDialog(null,
                    EditorLang.text("projectSettings.readFailed", String.valueOf(e.getMessage())),
                    EditorLang.text("projectSettings.title"), JOptionPane.ERROR_MESSAGE);
            return new JavelinSetting();
        }
    }

    // ---------- 界面装配 ----------

    private void buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 2));
        root.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        DefaultListModel<String> navModel = new DefaultListModel<>();
        for (SettingsSchema page : pages) {
            navModel.addElement(EditorLang.text(page.labelKey()));
        }
        JList<String> categories = new JList<>(navModel);
        categories.setSelectedIndex(0);
        JScrollPane navScroll = new JScrollPane(categories);
        navScroll.setPreferredSize(new Dimension(132, 0));
        navScroll.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 2));

        cards = new JPanel(new CardLayout());
        displayPages.addAll(pages);
        for (int i = 0; i < displayPages.size(); i++) {
            JComponent view = pageForm(i);
            cardViews.add(view);
            cards.add(view, cardKey(displayPages.get(i)));
        }
        categories.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int index = categories.getSelectedIndex();
            if (index >= 0) {
                ((CardLayout) cards.getLayout()).show(cards, cardKey(pages.get(index)));
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navScroll, cards);
        split.setDividerSize(2);       // 紧凑分隔条
        split.setDividerLocation(132);
        split.setResizeWeight(0.0);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        setContentPane(root);
    }

    /** 单页表单（按注解展开的「分组 → 行」；行控件复用检查器叶子件） */
    private JComponent pageForm(int index) {
        SettingsSchema page = displayPages.get(index);
        // 只有编辑器启动读入的页（活实例）改动才需要推引擎；只写不读的页改的是打包配置
        Runnable onApplied = page.editorStartup() ? this::apply : () -> {
        };
        JPanel form = SettingsFormPanel.build(page, onApplied, this::showError,
                section -> resetSection(index, section));
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /**
     * 分组恢复默认：确认 → 回填字段声明初值 → 推引擎 → 原位换掉该页视图（控件回显默认值）。
     *
     * <p>写盘仍走关窗的 load-modify-save；只写不读的页（窗口/场景）只改项目文件那份，不推引擎。</p>
     */
    private void resetSection(int index, SettingsSchema.Section section) {
        SettingsSchema page = displayPages.get(index);
        int choice = JOptionPane.showConfirmDialog(this,
                EditorLang.text("projectSettings.resetConfirm", EditorLang.text(section.labelKey)),
                EditorLang.text("projectSettings.resetTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            page.reset(section);
            if (page.editorStartup()) {
                apply();
            }
            SettingsSchema fresh = page.refreshed();
            displayPages.set(index, fresh);
            JComponent view = pageForm(index);
            cards.remove(cardViews.get(index));
            cardViews.set(index, view);
            cards.add(view, cardKey(fresh), index);
            ((CardLayout) cards.getLayout()).show(cards, cardKey(fresh));
            cards.revalidate();
            cards.repaint();
        } catch (Throwable error) {
            showError(error);
        }
    }

    /** 卡片键：页在设置根上的字段名（{@code of} 开的单页退回页名键） */
    private static String cardKey(SettingsSchema page) {
        return page.fieldName() != null ? page.fieldName() : page.labelKey();
    }

    // ---------- 行为（即时生效 + 写盘） ----------

    /** 行改动生效：渲染设置单点下发 + Layer/Tag 注册表同步（层名/标签清单即时可见） */
    private void apply() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        editor.applyRenderSettings();
        JavelinSetting live = editor.setting();
        LayerAndTagManger.applyLayers(live.getLayerSettings());
        // 已删除标签从场景节点上摘引用（页改动是整表回写，差集由管理器判定）
        LayerAndTagManger.applyTags(live.getTagSettings(), editor.getEditorGameScene());
    }

    /**
     * 写盘（逐页 load-modify-save 进项目 {@code Game/javelinSettings.settings}）：每页按
     * {@link SettingsSchema#managedRoots} 只覆写自己管理的页根字段，文件里的其余字段
     * （其它页、窗口参数、引擎调试…）原样保留。
     */
    private void persist() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        try {
            File file = new File(editor.getProject().getGameDir(), SettingManager.JAVELIN_SETTINGS_FILE);
            JavelinSetting fileSetting = file.exists()
                    ? new BinaryImporter(file).importData(JavelinSetting.class)
                    : new JavelinSetting();
            for (SettingsSchema page : pages) {
                Object fileHost = InspectorMeta.readProperty(fileSetting, page.fieldName());
                for (String field : page.managedRoots()) {
                    InspectorMeta.writeProperty(fileHost, field,
                            InspectorMeta.readProperty(page.root(), field));
                }
            }
            new BinaryExporter(file).export(fileSetting);
        } catch (Exception e) {
            LOGGER.error("项目设置写盘失败", e);
            showError(e);
        }
    }

    /** 引擎设置活实例（{@link #show} 已保证编辑器就绪；仍缺失即显性抛） */
    private static JavelinSetting liveSetting() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            throw new IllegalStateException(EditorLang.text("projectSettings.editorMissing"));
        }
        return editor.setting();
    }

    /** 错误显性化（红线 15）：弹错误框，不静默消化异常态 */
    private void showError(Throwable error) {
        LOGGER.error("项目设置应用失败", error);
        JOptionPane.showMessageDialog(this,
                EditorLang.text("projectSettings.writeFailed", String.valueOf(error.getMessage())),
                EditorLang.text("projectSettings.title"), JOptionPane.ERROR_MESSAGE);
    }
}
