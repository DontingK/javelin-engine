package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.scene.component.Light;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.awt.event.ItemEvent;
import java.util.function.Consumer;

/**
 * 阴影分辨率下拉（SHADOW_RESOLUTION 通道，Unity ShadowResolution 预设档口径
 * ——2026-11-01 用户拍板不做 Custom 自定义）：预设档 256/512/1024/2048/4096，
 * 选中即提交。当前值非预设档（手改文件）时显示最近档、不自动回写——选中才落值。
 */
final class ShadowResolutionField extends JComboBox<String> implements FieldEditor {

    /** 预设档（Unity 档位；升序） */
    private static final int[] PRESETS = {256, 512, 1024, 2048, 4096};

    private final Consumer<String> commit;
    private boolean suppressReport;

    ShadowResolutionField(String value, Consumer<String> commit) {
        this.commit = commit;
        for (int preset : PRESETS) {
            addItem(Integer.toString(preset));
        }
        selectNearest(parseInt(value, Light.DEFAULT_RESOLUTION));
        addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED || suppressReport) {
                return;
            }
            commit.accept((String) e.getItem());
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
            selectNearest(parseInt(wireValue, Light.DEFAULT_RESOLUTION));
        } finally {
            suppressReport = false;
        }
    }

    /** 下拉无中间态，恒非编辑中 */
    @Override
    public boolean isEditing() {
        return false;
    }

    /** 选中最近预设档（非预设值仅显示对齐，不回写——选中才提交） */
    private void selectNearest(int resolution) {
        int clamped = Math.max(Light.MIN_RESOLUTION,
                Math.min(Light.MAX_RESOLUTION, resolution));
        int best = PRESETS[0];
        for (int preset : PRESETS) {
            if (Math.abs(preset - clamped) < Math.abs(best - clamped)) {
                best = preset;
            }
        }
        setSelectedItem(Integer.toString(best));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
