package cn.donting.javelin.editor.swing.inspector.widget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Inspector 控件值格式化工具：浮点显示 4 位小数（HALF_UP 去尾零）——与
 * InspectorReflector 收集侧同口径（控件显示与快照值一致）；附逗号组包/拆解
 * （VEC3/ROTATION/RECT/COLOR 的 wire 串格式）。
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class InsFormat {

    /** 浮点显示精度（对齐 InspectorReflector DISPLAY_DECIMALS） */
    private static final int DISPLAY_DECIMALS = 4;

    private InsFormat() {
        // 静态工具禁止实例化
    }

    /** 浮点显示串（4 位小数四舍五入并去尾零：0.30000012 → "0.3"） */
    public static String formatFloat(float value) {
        return new BigDecimal(Float.toString(value))
                .setScale(DISPLAY_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** 多分量逗号组包（"x,y,z" wire 串） */
    public static String join(float... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatFloat(values[i]));
        }
        return sb.toString();
    }

    /** 逗号串拆解（分量数不符/解析失败返 null——wire 契约恒足量，防御兜底） */
    public static float[] split(String wire, int count) {
        if (wire == null) {
            return null;
        }
        String[] parts = wire.split(",");
        if (parts.length != count) {
            return null;
        }
        float[] result = new float[count];
        try {
            for (int i = 0; i < count; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return result;
    }

    /** 字节数 → 人类可读串（B/KB/MB/GB，一位小数；纯展示用） */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
