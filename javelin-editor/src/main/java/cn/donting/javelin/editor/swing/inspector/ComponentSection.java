package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.editor.inspector.ComponentInspectorVo;
import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.swing.inspector.widget.JeNumberInput;
import cn.donting.javelin.editor.swing.inspector.widget.CollapsiblePanel;
import cn.donting.javelin.editor.swing.inspector.widget.ScrubDragger;
import cn.donting.javelin.editor.swing.SvgIcon;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组件分组（折叠头 + 字段行两列区，Inspector_Design §9 两列布局）。
 *
 * <p>分组头：enabled 勾选在头左侧（@InsField 回写闸门口径——enabled 提升到
 * 分组头不入字段行；required 组件如 Transform 不渲染勾选不可禁用）+ 组件图标
 * （@InsHeader.icon）居标题前。字段行 =
 * GridBagLayout 两列：label 列（gridx 0）宽自动=列内最宽（组内最长显示名）、
 * insets right=4px（label/控件列间隔拍板例外）；控件列 fill HORIZONTAL。
 * 组内字段按 fieldName 索引（面板 Transform 定点刷新用）。</p>
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

    public ComponentSection(Node owner, ComponentInspectorVo vo, FieldCommit commit, boolean initialCollapsed) {
        this.vo = vo;
        setLayout(new BorderLayout(0, 0));
        JComponent headerLeft = vo.required ? null : enabledCheck(owner, vo, commit);
        collapse = new CollapsiblePanel(vo.name, headerLeft, headerIcon(), buildFields(owner, vo, commit));
        collapse.setCollapsed(initialCollapsed);
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
        int row = 0;
        for (InspectorFieldVo field : vo.fields) {
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
            panel.add(editor.editorComponent(), gbc);
            row++;
        }
        return panel;
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
