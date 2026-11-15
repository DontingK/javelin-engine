package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.swing.inspector.FieldEditor;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Consumer;

/**
 * 滑条字段（FLOAT + {@code @InsSlider}）：左侧滑条、右侧数字框（Unity Slider 行口径）。
 *
 * <p>滑条按 {@code @InsFloatRange} 的 step 换算刻度（0~ticks 映射 [min,max]），拖动即提交；
 * 数字框沿用 {@link JeNumberInput}（键入即提交、range 硬钳、label 可拖调），两者双向同步且
 * 互相抑制上报防回声。数字框带固定宽度，滑条占满余量。</p>
 *
 * @author javelin
 * @create 2026/11/15
 **/
public class JeSliderField extends JPanel implements FieldEditor {

    /** 右侧数字框宽度（像素；滑条占满余量） */
    private static final int NUMBER_WIDTH = 58;

    private final JSlider slider;
    private final JeNumberInput number;
    private final float min;
    private final float step;
    /** 程序化同步抑制（滑条↔数字框互相回写时不重复上报） */
    private boolean suppress;

    public JeSliderField(String initial, InspectorFieldVo.Range range, Consumer<String> commit) {
        this.min = range.min;
        float span = range.max - range.min;
        this.step = range.step > 0f ? range.step : span / 100f;
        int ticks = Math.max(1, (int) Math.round(span / step));
        slider = new JSlider(0, ticks);
        slider.setOpaque(false);
        slider.setPaintTicks(false);
        slider.setPaintLabels(false);
        slider.setFocusable(false);
        slider.setValue(tickOf(initial));
        // 数字框提交 → 滑条跟随（抑制滑条回声），再上报
        number = new JeNumberInput(initial, false, range, value -> {
            suppress = true;
            try {
                slider.setValue(tickOf(value));
            } finally {
                suppress = false;
            }
            commit.accept(value);
        });
        number.setScrubStep(step);
        Dimension pref = number.getPreferredSize();
        number.setPreferredSize(new Dimension(NUMBER_WIDTH, pref.height));
        // 滑条拖动 → 数字框跟随（setValue 不回调）→ 上报
        slider.addChangeListener(e -> {
            if (suppress) {
                return;
            }
            String wire = InsFormat.formatFloat(valueOf(slider.getValue()));
            number.setValue(wire);
            commit.accept(wire);
        });
        setOpaque(false);
        setLayout(new BorderLayout(2, 0));
        add(slider, BorderLayout.CENTER);
        add(number, BorderLayout.EAST);
    }

    /** 值 → 滑条刻度（按 step 取整并钳制到 [0,ticks]） */
    private int tickOf(String wire) {
        Float parsed = wire != null ? parse(wire) : null;
        return tickOf(parsed != null ? parsed : min);
    }

    private int tickOf(float value) {
        float clamped = Math.max(min, Math.min(min + step * slider.getMaximum(), value));
        return (int) Math.round((clamped - min) / step);
    }

    /** 滑条刻度 → 值 */
    private float valueOf(int tick) {
        return min + tick * step;
    }

    private static Float parse(String wire) {
        try {
            return Float.parseFloat(wire.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    @Override
    public JComponent editorComponent() {
        return this;
    }

    @Override
    public void setValue(String wireValue) {
        if (isEditing()) {
            return;
        }
        Float parsed = parse(wireValue);
        if (parsed == null) {
            return;
        }
        suppress = true;
        try {
            slider.setValue(tickOf(parsed));
        } finally {
            suppress = false;
        }
        number.setValue(wireValue);
    }

    @Override
    public boolean isEditing() {
        return number.isEditing();
    }
}
