package com.javelin.editor.swing.inspector;

import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.inspector.InspectorValueType;
import com.javelin.editor.swing.inspector.widget.AssetInput;
import com.javelin.editor.swing.inspector.widget.ColorField;
import com.javelin.editor.swing.inspector.widget.JeNumberInput;
import com.javelin.editor.swing.inspector.widget.JeSelect;
import com.javelin.editor.swing.inspector.widget.JeTextInput;
import com.javelin.editor.swing.inspector.widget.LayerMaskField;
import com.javelin.editor.swing.inspector.widget.VectorRow;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * 字段行控件工厂：{@link InspectorValueType} → 行控件（{@link FieldEditor}）。
 * 控件消费 wire 值串、提交回写回调——类型解析与回写语义全在
 * {@code InspectorReflector}，控件零反射感知。
 *
 * <p>TAG 为节点头专用回写通道、非字段行类型（收集面不产出，走到即抛错）；
 * LAYER_MASK 的层名选项已随 VO 下发（M4 补发，Reflector buildProp）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class FieldRowFactory {

    private FieldRowFactory() {
        // 静态工厂禁止实例化
    }

    /**
     * 按字段 VO 的值类型创建行控件。
     *
     * @param vo     字段 VO（值/范围/选项/资产显示名）
     * @param commit 提交回调（wire 值串；宿主与字段名由调用方闭包携带）
     */
    public static FieldEditor create(InspectorFieldVo vo, Consumer<String> commit) {
        InspectorValueType type = InspectorValueType.valueOf(vo.valueType);
        switch (type) {
            case INT:
            case FLOAT: {
                JeNumberInput input = new JeNumberInput(vo.value, type == InspectorValueType.INT,
                        vo.range, commit);
                if (vo.range != null) {
                    // 拖动调值每像素步长跟随注解 step（无 range 走控件默认：整 1/浮 0.1）
                    input.setScrubStep(vo.range.step);
                }
                return input;
            }
            case STRING:
                return new JeTextInput(vo.value, commit);
            case BOOLEAN:
                return new BooleanField(vo.value, commit);
            case ENUM:
                return new JeSelect(vo.options, vo.value, commit);
            case VEC3_F:
                return new VectorRow(new String[]{"X", "Y", "Z"}, vo.value, false, vo.range, commit);
            case ROTATION:
                return new VectorRow(new String[]{"X", "Y", "Z"}, vo.value, false, null, commit);
            case RECT:
                // 两行 X/Y + W/H（Web 版 v0.2 形态回归，2026-10-18 用户拍板）
                return new VectorRow(new String[]{"X", "Y", "W", "H"}, vo.value, false, null, commit, 2);
            case COLOR:
                return new ColorField(vo.value, commit);
            case LAYER_MASK:
                return new LayerMaskField(vo.value, vo.options, commit);
            case GUID_ASSET:
                if (vo.assetType == null) {
                    throw new IllegalStateException("GUID_ASSET 字段缺声明资产类型: " + vo.fieldName);
                }
                return new AssetInput(vo.assetType, commit);
            default:
                throw new IllegalStateException("字段行不支持的值类型: " + type);
        }
    }

    /** 布尔字段（BOOLEAN 通道）：勾选即时回写；程序化置值抑制上报防回声 */
    private static final class BooleanField extends JCheckBox implements FieldEditor {

        private final Consumer<String> commit;
        private boolean suppressReport;

        private BooleanField(String value, Consumer<String> commit) {
            this.commit = commit;
            setMargin(new Insets(0, 0, 0, 0));
            setSelected("true".equals(value));
            addItemListener(e -> {
                if (suppressReport) {
                    return;
                }
                commit.accept(String.valueOf(isSelected()));
            });
        }

        @Override
        public JComponent editorComponent() {
            return this;
        }

        @Override
        public void setValue(String wireValue) {
            suppressReport = true;
            try {
                setSelected("true".equals(wireValue));
            } finally {
                suppressReport = false;
            }
        }

        @Override
        public boolean isEditing() {
            return false;
        }
    }
}
