package com.javelin.editor.swing.inspector.widget;

import com.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * 颜色字段（COLOR 通道）：色块按钮 + JColorChooser 拾色（2026-10-18 拍板）。
 * alpha 不进拾色器、保留原值（背景色 alpha 无视觉意义）；value=null（可空字段
 * 未物化）以白色预览，首次选取即物化显式值（Inspector_Design 可空值口径）。
 *
 * <p>色块颜色 = 字段数据本身（非 UI 配色）；wire 串 "r,g,b,a" 0-1 浮点。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class ColorField extends JButton implements FieldEditor {

    private static final int BLOCK_HEIGHT = 18;
    private static final int ARC = 3;

    private final Consumer<String> commit;
    private float r = 1f;
    private float g = 1f;
    private float b = 1f;
    private float a = 1f;

    public ColorField(String wireValue, Consumer<String> commit) {
        this.commit = commit;
        setContentAreaFilled(false);
        setFocusable(false);
        setMargin(new Insets(1, 2, 1, 2));
        float[] parts = InsFormat.split(wireValue, 4);
        if (parts != null) {
            r = parts[0];
            g = parts[1];
            b = parts[2];
            a = parts[3];
        }
        addActionListener(e -> pickColor());
    }

    /** 拾色（模态对话框；取消不动；alpha 保留原值） */
    private void pickColor() {
        Color initial = new Color(clamp01(r), clamp01(g), clamp01(b));
        Color picked = JColorChooser.showDialog(this, "选择颜色", initial);
        if (picked == null) {
            return;
        }
        r = picked.getRed() / 255f;
        g = picked.getGreen() / 255f;
        b = picked.getBlue() / 255f;
        repaint();
        commit.accept(InsFormat.join(clamp01(r), clamp01(g), clamp01(b), clamp01(a)));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        return new Dimension(Math.max(preferred.width, 32), BLOCK_HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        // 参数名不取 g：字段 g（绿色分量）会被遮蔽（2026-10-18 编译实锤）
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(clamp01(r), clamp01(g), clamp01(b)));
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC));
            g2.setColor(lineColor());
            g2.draw(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC));
        } finally {
            g2.dispose();
        }
    }

    private static Color lineColor() {
        Color color = UIManager.getColor("Component.borderColor");
        if (color == null) {
            color = UIManager.getColor("Separator.foreground");
        }
        return color != null ? color : new Color(0xBB, 0xBB, 0xBB);
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        float[] parts = InsFormat.split(wireValue, 4);
        if (parts == null) {
            return;
        }
        r = parts[0];
        g = parts[1];
        b = parts[2];
        a = parts[3];
        repaint();
    }

    @Override
    public boolean isEditing() {
        return false;
    }
}
