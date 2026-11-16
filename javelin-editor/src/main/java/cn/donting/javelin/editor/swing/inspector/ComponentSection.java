package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.editor.inspector.ComponentInspectorVo;
import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.swing.inspector.widget.JeNumberInput;
import cn.donting.javelin.editor.swing.inspector.widget.JeSelect;
import cn.donting.javelin.editor.swing.inspector.widget.CollapsiblePanel;
import cn.donting.javelin.editor.swing.inspector.widget.ScrubDragger;
import cn.donting.javelin.editor.swing.SvgIcon;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 组件分组（折叠头 + 字段行两列区，Inspector_Design §9 两列布局）。
 *
 * <p>分组头：enabled 勾选在头左侧（@InsField 回写闸门口径——enabled 提升到
 * 分组头不入字段行；required 组件如 Transform 不渲染勾选不可禁用）+ 组件图标
 * （@InsHeader.icon）居标题前。字段行 =
 * GridBagLayout 两列：label 列（gridx 0）宽自动=列内最宽（组内最长显示名）、
 * insets right=4px（label/控件列间隔拍板例外）；控件列 fill HORIZONTAL。
 * 组内字段按 fieldName 索引（面板 Transform 定点刷新用）。头右键菜单由宿主注入
 * （CollapsiblePanel.setHeaderPopupSupplier，懒构建；null = 无菜单——策略归宿主，
 * 本件只管透传）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class ComponentSection extends JComponent {

    /** 组件头图标边长（头高 22 内取 16px 档，Unity 组件头口径） */
    private static final int ICON_SIZE = 16;

    private final Map<String, FieldEditor> editors = new LinkedHashMap<>();
    private final CollapsiblePanel collapse;
    private final ComponentInspectorVo vo;

    public ComponentSection(Node owner, ComponentInspectorVo vo, FieldCommit commit, boolean initialCollapsed,
                            Supplier<JPopupMenu> headerPopup) {
        this.vo = vo;
        setLayout(new BorderLayout(0, 0));
        JComponent headerLeft = vo.required ? null : enabledCheck(owner, vo, commit);
        collapse = new CollapsiblePanel(vo.name, headerLeft, headerIcon(), buildFields(owner, vo, commit));
        collapse.setCollapsed(initialCollapsed);
        collapse.setHeaderPopupSupplier(headerPopup);
        add(collapse, BorderLayout.CENTER);
    }

    /** 组件头图标（@InsHeader.icon → /icon/{name}.svg；未配/资源缺失不渲染——不占空位，菜单域同口径） */
    private Icon headerIcon() {
        if (vo.icon == null || vo.icon.isBlank()) {
            return null;
        }
        SvgIcon icon = SvgIcon.of(vo.icon, ICON_SIZE);
        return icon.isAvailable() ? icon : null;
    }

    private JComponent enabledCheck(Node owner, ComponentInspectorVo vo, FieldCommit commit) {
        JCheckBox check = new JCheckBox();
        check.setSelected(vo.enabled);
        check.addItemListener(e -> commit.commit(owner, vo.component, "enabled", "BOOLEAN",
                String.valueOf(check.isSelected())));
        return check;
    }

    private JComponent buildFields(Node owner, ComponentInspectorVo vo, FieldCommit commit) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        // 顶对齐（§9 拍板）：RECT 两行形态下 label 与多行控件顶部对齐；
        // 单行控件视觉差 1~2px 可接受（Web 版同口径未用 baseline）
        gbc.anchor = GridBagConstraints.NORTHWEST;
        // 条件子组（@InsFieldGroup）：父字段名 → 该组状态（组头 + 组内行，成组显隐/折叠）
        Map<String, GroupState> groups = new LinkedHashMap<>();
        int row = 0;
        for (InspectorFieldVo field : vo.fields) {
            // 组头行（组首行携带；占整行宽，点击折叠）——插在该组第一行之前
            if (field.parentField != null && field.groupLabel != null) {
                GroupState state = groups.computeIfAbsent(field.parentField, k -> new GroupState());
                state.collapsed = field.groupCollapsed;
                JButton header = groupHeader(field.groupLabel, state, panel);
                gbc.gridy = row++;
                gbc.gridx = 0;
                gbc.gridwidth = 2;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.weightx = 1;
                gbc.insets = new Insets(2, 0, 1, 0);
                panel.add(header, gbc);
                // 复位单列布局
                gbc.gridwidth = 1;
                gbc.weightx = 0;
                state.header = header;
            }
            FieldEditor editor = FieldRowFactory.create(field, value ->
                    commit.commit(owner, vo.component, field.fieldName, field.valueType, value));
            editors.put(field.fieldName, editor);
            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;
            gbc.insets = new Insets(1, 0, 1, 4);
            JLabel fieldLabel = new JLabel(field.name);
            if (editor instanceof JeNumberInput) {
                // label 按住左键左右拖调值（Unity scrub 口径）
                ScrubDragger.attach(fieldLabel, (JeNumberInput) editor);
            }
            panel.add(fieldLabel, gbc);
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1;
            gbc.insets = new Insets(1, 0, 1, 0);
            JComponent editorComponent = editor.editorComponent();
            panel.add(editorComponent, gbc);
            if (field.parentField != null) {
                // 同组多行共享（label 与控件各一个 cell）——GridBag 忽略不可见组件，成组隐去即整行塌缩
                GroupState state = groups.computeIfAbsent(field.parentField, k -> new GroupState());
                state.showKeys = field.showKeys;
                state.members.add(fieldLabel);
                state.members.add(editorComponent);
            }
            row++;
        }
        bindGroupVisibility(panel, vo, groups);
        return panel;
    }

    /**
     * 折叠组头（Unity 口径）：扁平按钮 + 三角箭头，点击在收起/展开间切换；
     * 初始收起态取 {@code @InsFieldGroup.collapsed()}，文本前缀随态更新。
     */
    private JButton groupHeader(String label, GroupState state, JPanel panel) {
        JButton header = new JButton();
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setOpaque(false);
        header.setContentAreaFilled(false);
        header.setBorderPainted(false);
        header.setFocusPainted(false);
        header.setMargin(new Insets(2, 0, 2, 0));
        applyHeaderText(header, label, state.collapsed);
        header.addActionListener(e -> {
            state.collapsed = !state.collapsed;
            applyHeaderText(header, label, state.collapsed);
            refreshGroup(state);
            relayoutAfterVisibility(panel);
        });
        return header;
    }

    /** 组头文本（▾ 展开 / ▸ 收起） */
    private static void applyHeaderText(JButton header, String label, boolean collapsed) {
        header.setText((collapsed ? "▸ " : "▾ ") + label);
    }

    /**
     * 条件子组显隐绑定：父枚举行（{@link JeSelect}）选中值变化 → 整组（含组头）显隐；
     * 组头点击 → 折叠切换（两条件与关系：父值命中且未收起才显示组内行）。
     */
    private void bindGroupVisibility(JPanel panel, ComponentInspectorVo vo,
                                     Map<String, GroupState> groups) {
        for (Map.Entry<String, GroupState> entry : groups.entrySet()) {
            GroupState state = entry.getValue();
            String parentValue = vo.fields.stream()
                    .filter(f -> entry.getKey().equals(f.fieldName))
                    .map(f -> f.value)
                    .findFirst().orElse(null);
            state.parentValue = parentValue;
            refreshGroup(state);
            FieldEditor parentEditor = editors.get(entry.getKey());
            if (parentEditor instanceof JeSelect select) {
                select.addActionListener(e -> {
                    Object selected = select.getSelectedItem();
                    String value = selected instanceof InspectorFieldVo.Option option ? option.value : null;
                    state.parentValue = value;
                    refreshGroup(state);
                    relayoutAfterVisibility(panel);
                });
            }
        }
    }

    /** 组显隐：组头随父值命中；组内行还要求未收起 */
    private static void refreshGroup(GroupState state) {
        boolean parentOn = state.parentValue != null && state.showKeys != null
                && state.showKeys.contains(state.parentValue);
        if (state.header != null) {
            state.header.setVisible(parentOn);
        }
        boolean show = parentOn && !state.collapsed;
        for (JComponent member : state.members) {
            member.setVisible(show);
        }
    }

    /**
     * 组显隐变化后的重排：内层面板标脏 + 祖先链逐级 {@code invalidate()}，再由最近的
     * validate root 重新校验（实现与整段折叠共用 {@link CollapsiblePanel#refreshLayoutAfterVisibility}）。
     *
     * <p>只 {@code revalidate()} 内层面板是不够的（2026-11-16 实机 bug 的根因）：
     * {@code Container#getPreferredSize()} 在 {@code valid} 时直接返回缓存的 prefSize，
     * 展开子组后外层的 {@code InsScrollablePanel} 与 {@code JViewport} 仍报「折叠态」的高度，
     * viewport 于是只给内层面板一个比 pref 矮的格子；{@code GridBagLayout} 在容器矮于 pref 时
     * 按各控件 minimum 尺寸压缩行，而颜色行是 {@link javax.swing.JButton} 子类
     * （{@link cn.donting.javelin.editor.swing.inspector.widget.ColorField}，minimum 高度≈0），
     * 独吞全部压缩，色块被压成一条 3px 的横线。
     * 回归口径见 {@code InspectorRowHeightTest}。</p>
     */
    private static void relayoutAfterVisibility(JComponent panel) {
        CollapsiblePanel.refreshLayoutAfterVisibility(panel);
        panel.repaint();
    }

    /** 条件子组运行态（组头 / 组内行 / 显示条件 / 折叠态） */
    private static final class GroupState {
        private final List<JComponent> members = new ArrayList<>();
        private List<String> showKeys;
        private String parentValue;
        private JButton header;
        private boolean collapsed;
    }

    /** 组内字段控件（按字段名；无此字段 null——Transform 定点刷新用） */
    public FieldEditor getEditor(String fieldName) {
        return editors.get(fieldName);
    }

    /** 组内任一控件编辑中（面板重建保护） */
    public boolean isEditing() {
        for (FieldEditor editor : editors.values()) {
            if (editor.isEditing()) {
                return true;
            }
        }
        return false;
    }

    /** 当前折叠态（面板跨重建记忆用） */
    public boolean isCollapsed() {
        return collapse.isCollapsed();
    }

    /** 组件显示名（折叠态记忆键） */
    public String getComponentName() {
        return vo.name;
    }
}
