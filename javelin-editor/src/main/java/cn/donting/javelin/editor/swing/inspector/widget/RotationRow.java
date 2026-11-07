package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.editor.inspector.RotationMath;
import cn.donting.javelin.editor.swing.inspector.FieldEditor;
import org.joml.Quaternionf;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * 旋转字段行（ROTATION 通道）：{@link VectorRow}（X/Y/Z 三段）+ 自旋回声抑制。
 * 外源刷新值为本行近期写出的同一旋转（含规范翻转的等价三元组）时跳过回写，
 * 保留用户输入真值；真正的外部变更照常刷新。口径与根因见 Inspector_Design v0.15。
 *
 * @author javelin
 * @create 2026/11/7
 */
public class RotationRow implements FieldEditor {

    /** 近期写入环容量（覆盖 EDT 提交与引擎帧边界的拖动滞后窗口） */
    private static final int RECENT_WRITES = 8;

    private final VectorRow row;
    private final Quaternionf[] recentWrites = new Quaternionf[RECENT_WRITES];
    private int writeCursor;

    public RotationRow(String wireValue, Consumer<String> commit) {
        row = new VectorRow(new String[]{"X", "Y", "Z"}, wireValue, false, null, wire -> {
            record(parse(wire));
            commit.accept(wire);
        });
        record(parse(wireValue)); // 初始显示值入环：同旋转的首次回显不算外部变更
    }

    @Override
    public JComponent editorComponent() {
        return row.editorComponent();
    }

    @Override
    public void setValue(String wireValue) {
        Quaternionf incoming = parse(wireValue);
        if (incoming != null && isRecent(incoming)) {
            return; // 自旋回声：规范翻转的等价表示不覆盖用户输入真值
        }
        row.setValue(wireValue);
    }

    @Override
    public boolean isEditing() {
        return row.isEditing();
    }

    private void record(Quaternionf rotation) {
        if (rotation == null) {
            return;
        }
        recentWrites[writeCursor] = rotation;
        writeCursor = (writeCursor + 1) % RECENT_WRITES;
    }

    private boolean isRecent(Quaternionf rotation) {
        for (Quaternionf recent : recentWrites) {
            if (recent != null && RotationMath.sameRotation(recent, rotation)) {
                return true;
            }
        }
        return false;
    }

    /** 度串 "x,y,z" → 四元数（非法串返 null——wire 契约恒足量，防御兜底） */
    private static Quaternionf parse(String wire) {
        float[] deg = InsFormat.split(wire, 3);
        return deg == null ? null : RotationMath.fromDegrees(deg[0], deg[1], deg[2]);
    }
}
