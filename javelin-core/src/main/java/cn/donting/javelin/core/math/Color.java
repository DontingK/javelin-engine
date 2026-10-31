package cn.donting.javelin.core.math;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

import java.util.Locale;

/**
 * RGBA 颜色——值类型，参与序列化对象图（宿主资产可直接持有 Color 字段）。
 *
 * <p>每个分量范围 [0, 1]，构造时自动夹取；常量（WHITE 等）为共享实例，
 * 反序列化产生 equals 相等的新实例。引擎内传递统一走本类，
 * MaterialBlock / clearColor / uniform 落盘时经 {@link #toArray()} 展开。</p>
 *
 * <p>序列化（Capsule 协议，红线 9/10）：字段非 final（反序列化回写），
 * 无参构造（ReflectiveFactory 重建），write/read 四分量直通。</p>
 *
 * @author donting
 * @since 1.0
 */
@Reflective
public class Color implements Serializable {

    public static final Color WHITE = new Color(1, 1, 1, 1);
    public static final Color BLACK = new Color(0, 0, 0, 1);
    public static final Color RED = new Color(1, 0, 0, 1);
    public static final Color GREEN = new Color(0, 1, 0, 1);
    public static final Color BLUE = new Color(0, 0, 1, 1);
    public static final Color YELLOW = new Color(1, 1, 0, 1);
    public static final Color CYAN = new Color(0, 1, 1, 1);
    public static final Color MAGENTA = new Color(1, 0, 1, 1);
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    // 非 final：序列化反序列化需回写（红线 9）；运行期不可变靠 API 面——无 setter
    public float r;
    public float g;
    public float b;
    public float a;

    /** 反序列化用无参构造（ReflectiveFactory 重建，默认全黑透明） */
    public Color() {
    }

    /**
     * 创建颜色（各分量自动夹取到 [0, 1]）。
     *
     * @param r 红色分量 (0-1)
     * @param g 绿色分量 (0-1)
     * @param b 蓝色分量 (0-1)
     * @param a 透明度分量 (0-1)
     */
    public Color(float r, float g, float b, float a) {
        this.r = clamp01(r);
        this.g = clamp01(g);
        this.b = clamp01(b);
        this.a = clamp01(a);
    }

    /**
     * 创建不透明颜色。
     *
     * @param r 红色 (0-1)
     * @param g 绿色 (0-1)
     * @param b 蓝色 (0-1)
     */
    public Color(float r, float g, float b) {
        this(r, g, b, 1.0f);
    }

    // ═══ 序列化（字段直通，红线 10） ═══

    @Override
    public void write(OutputCapsule output) {
        output.write("r", r);
        output.write("g", g);
        output.write("b", b);
        output.write("a", a);
    }

    @Override
    public void read(InputCapsule input) {
        r = input.readFloat("r");
        g = input.readFloat("g");
        b = input.readFloat("b");
        a = input.readFloat("a");
    }

    // ═══ 工厂 ═══

    /**
     * 从 0-255 整数创建颜色。
     *
     * @param r 红色 (0-255)
     * @param g 绿色 (0-255)
     * @param b 蓝色 (0-255)
     * @param a 透明度 (0-255)
     */
    public static Color fromBytes(int r, int g, int b, int a) {
        return new Color(r / 255.0f, g / 255.0f, b / 255.0f, a / 255.0f);
    }

    /**
     * 从十六进制字符串创建颜色（编辑器/资产配置常用）。
     *
     * <p>支持 {@code #RGB}、{@code #RRGGBB}、{@code #RRGGBBAA} 三种形式，
     * {@code #} 前缀可选；形如 Unity 的 {@code #1A2B3C4D}。</p>
     *
     * @throws IllegalArgumentException 格式非法或位数不对
     */
    public static Color fromHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex must not be null");
        }
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return switch (s.length()) {
                case 3 -> new Color(
                        Integer.parseInt(s.substring(0, 1), 16) / 15.0f,
                        Integer.parseInt(s.substring(1, 2), 16) / 15.0f,
                        Integer.parseInt(s.substring(2, 3), 16) / 15.0f);
                case 6 -> fromBytes(
                        Integer.parseInt(s.substring(0, 2), 16),
                        Integer.parseInt(s.substring(2, 4), 16),
                        Integer.parseInt(s.substring(4, 6), 16), 255);
                case 8 -> fromBytes(
                        Integer.parseInt(s.substring(0, 2), 16),
                        Integer.parseInt(s.substring(2, 4), 16),
                        Integer.parseInt(s.substring(4, 6), 16),
                        Integer.parseInt(s.substring(6, 8), 16));
                default -> throw new IllegalArgumentException(
                        "hex 位数非法（支持 #RGB / #RRGGBB / #RRGGBBAA）: " + hex);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("hex 含非十六进制字符: " + hex, e);
        }
    }

    // ═══ 转换 ═══

    /**
     * 转换为 RGBA 打包整数（各分量最高有效字节在前，RGBA 顺序）。
     */
    public int toRGBA() {
        int ir = (int) (r * 255) & 0xFF;
        int ig = (int) (g * 255) & 0xFF;
        int ib = (int) (b * 255) & 0xFF;
        int ia = (int) (a * 255) & 0xFF;
        return (ir << 24) | (ig << 16) | (ib << 8) | ia;
    }

    /**
     * 转换为十六进制字符串（含 # 前缀；{@code #RRGGBB}，忽略 alpha）。
     */
    public String toHex() {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                (int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    /**
     * 展开为 float[]{r, g, b, a}（MaterialBlock.setColor / clearColor / uniform 用）。
     */
    public float[] toArray() {
        return new float[]{r, g, b, a};
    }

    // ═══ 运算 ═══

    /**
     * 线性混合两种颜色（t 夹取 [0, 1]，结果新实例）。
     *
     * @param other 另一个颜色
     * @param t 混合因子（0 = 本色，1 = other）
     */
    public Color lerp(Color other, float t) {
        float k = clamp01(t);
        return new Color(
                lerp(r, other.r, k),
                lerp(g, other.g, k),
                lerp(b, other.b, k),
                lerp(a, other.a, k));
    }

    // ═══ 序列化/相等 ═══

    /**
     * 从 "r,g,b,a" 字符串解析（文本配置/日志反解，各段自动 trim）。
     */
    public static Color fromString(String s) {
        if (s == null) {
            throw new IllegalArgumentException("color string must not be null");
        }
        String[] parts = s.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("颜色字符串应为 4 段逗号分隔（r,g,b,a）: " + s);
        }
        return new Color(
                Float.parseFloat(parts[0].trim()),
                Float.parseFloat(parts[1].trim()),
                Float.parseFloat(parts[2].trim()),
                Float.parseFloat(parts[3].trim()));
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", r, g, b, a);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Color other)) {
            return false;
        }
        return Float.compare(r, other.r) == 0
                && Float.compare(g, other.g) == 0
                && Float.compare(b, other.b) == 0
                && Float.compare(a, other.a) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(r);
        result = 31 * result + Float.hashCode(g);
        result = 31 * result + Float.hashCode(b);
        result = 31 * result + Float.hashCode(a);
        return result;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
