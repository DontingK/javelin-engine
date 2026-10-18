package com.javelin.editor.swing.inspector;

import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.inspector.NodeInspectorVo;
import com.javelin.editor.swing.SvgIcon;
import com.javelin.editor.swing.inspector.widget.JeSelect;
import com.javelin.editor.swing.inspector.widget.JeTextInput;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点头区（基础信息：name/active/layer/tag 四件套——Inspector_Design §5
 * 节点头口径：手拼 VO 不走字段行，@InsField 在 Node 三字段上仅为回写闸门）。
 *
 * <p>布局对齐 Web 版 EntityInspectorPanel（2026-10-18 用户派发回归）：节点图标
 * （20px，<b>占左侧两行</b>、垂直居中——Unity 节点头口径）+ 右侧两行——行 0 =
 * 激活勾选 + 名称输入；行 1 = 「图层」「标签」两下拉<b>并排单行</b>（原
 * entity-meta-row 单行双 meta-field 形态，中文文案口径同 Web 拍板）。<b>两行不共享
 * GridBag 列</b>——结构不同的行共享一个 GridBag 会跨行牵制列宽（图标列被 label
 * 撑宽/勾选列与下拉列混权，2026-10-18 实测布局错乱实锤）：外层 GridBag 仅两格
 * （图标占两行 + 右侧单列），各行内部独立布局。</p>
 *
 * <p>name=blur/enter 提交（JeTextInput）；active=勾选即时回写；layer=INT 索引串
 * （经 {@code Layer.of} 换算，{@code setLayer} setter）；tag=TAG 替换式单选
 * （"Untagged"=清空）。两下拉当前值不在选项表时补临时选项（未命名层
 * "Layer N" / 自定义 tag 名）防静默失配。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class NodeHeaderPanel extends JComponent {

    private static final String UNTAGGED = "Untagged";

    /** 节点图标尺寸（2026-10-18 用户指定 36） */
    private static final int NODE_ICON_SIZE = 36;

    private final NodeInspectorVo vo;
    private final FieldCommit commit;
    private JeTextInput nameField;

    public NodeHeaderPanel(NodeInspectorVo vo, FieldCommit commit) {
        this.vo = vo;
        this.commit = commit;
        setLayout(new GridBagLayout());

        // 图标占左列两行（垂直居中于名称行+图层标签行）
        SvgIcon nodeIcon = SvgIcon.of("box", NODE_ICON_SIZE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridheight = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(1, 0, 1, 2);
        if (nodeIcon.isAvailable()) {
            add(new JLabel(nodeIcon), gbc);
        }

        // 右列：行 0 名称行 / 行 1 图层标签行（各自独立内部布局）
        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;
        gbc.insets = new Insets(1, 0, 1, 0);
        add(buildNameRow(), gbc);
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 1, 0);
        add(buildMetaRow(), gbc);
    }

    /** 行 0：激活勾选 + 名称输入拉满剩余宽 */
    private JComponent buildNameRow() {
        JPanel row = new JPanel(new BorderLayout(2, 0));
        row.setOpaque(false);
        JCheckBox activeCheck = new JCheckBox();
        activeCheck.setMargin(new Insets(0, 0, 0, 0));
        activeCheck.setSelected(vo.active);
        activeCheck.addItemListener(e ->
                commitValue("active", "BOOLEAN", String.valueOf(activeCheck.isSelected())));
        row.add(activeCheck, BorderLayout.WEST);
        nameField = new JeTextInput(vo.nodeName, text -> commitValue("name", "STRING", text));
        row.add(nameField, BorderLayout.CENTER);
        return row;
    }

    /** 行 1：图层 + 标签 两下拉并排等分（独立 GridBag，不与行 0 共享列） */
    private JComponent buildMetaRow() {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 0);
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 4);
        row.add(new JLabel("图层"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.insets = new Insets(0, 0, 0, 2);
        row.add(layerSelect(), gbc);
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 0, 0, 4);
        row.add(new JLabel("标签"), gbc);
        gbc.gridx = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        row.add(tagSelect(), gbc);
        return row;
    }

    private JComponent layerSelect() {
        List<InspectorFieldVo.Option> options = new ArrayList<>();
        String current = String.valueOf(vo.layer);
        boolean currentListed = false;
        if (vo.layerNames != null) {
            for (int i = 0; i < vo.layerNames.size(); i++) {
                String name = vo.layerNames.get(i);
                if (name != null && !name.isBlank()) {
                    options.add(option(String.valueOf(i), name));
                    if (i == vo.layer) {
                        currentListed = true;
                    }
                }
            }
        }
        if (!currentListed) {
            // 当前层未命名（空槽）——补临时选项防下拉静默失配
            options.add(option(current, "Layer " + vo.layer));
        }
        return new JeSelect(options, current, value -> commitValue("layer", "INT", value));
    }

    private JComponent tagSelect() {
        List<InspectorFieldVo.Option> options = new ArrayList<>();
        String current = vo.tags != null && !vo.tags.isEmpty() ? vo.tags.get(0) : UNTAGGED;
        boolean currentListed = false;
        if (vo.tagNames != null) {
            for (String name : vo.tagNames) {
                options.add(option(name, name));
                if (name.equals(current)) {
                    currentListed = true;
                }
            }
        }
        if (!currentListed) {
            // 自定义 tag（不在注册表）——补临时选项
            options.add(option(current, current));
        }
        return new JeSelect(options, current, value -> commitValue("tags", "TAG", value));
    }

    private static InspectorFieldVo.Option option(String value, String label) {
        InspectorFieldVo.Option option = new InspectorFieldVo.Option();
        option.value = value;
        option.label = label;
        return option;
    }

    private void commitValue(String fieldName, String valueType, String wireValue) {
        commit.commit(vo.node, vo.node, fieldName, valueType, wireValue);
    }

    /** 名称行编辑中（面板重建保护） */
    public boolean isEditing() {
        return nameField.isEditing();
    }
}
