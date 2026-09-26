package com.javelin.core.math;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

import java.util.Locale;

/**
 * 矩形——值类型，参与序列化对象图（宿主资产可直接持有 Rect 字段）。
 *
 * <p>x/y/w/h 四分量（不做夹取，坐标语义由使用方约定）。相机视口
 * {@code viewportRect} 以 [0, 1] 归一化矩形使用：<b>原点左下、y 向上</b>
 * （Unity normalizedViewPortRect 口径，用户拍板 2026-09-26）。</p>
 *
 * <p>序列化（Capsule 协议，红线 9/10）：字段非 final（反序列化回写），
 * 无参构造（ReflectiveFactory 重建），write/read 四分量直通。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Rect implements Serializable {

    // 非 final：序列化反序列化需回写（红线 9）；运行期不可变靠 API 面——无 setter
    public float x;
    public float y;
    public float width;
    public float height;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public Rect() {
    }

    /**
     * 创建矩形。
     *
     * @param x      左上角 x
     * @param y      左上角 y
     * @param width  宽
     * @param height 高
     */
    public Rect(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    // ═══ 序列化（字段直通，红线 10） ═══

    @Override
    public void write(OutputCapsule output) {
        output.write("x", x);
        output.write("y", y);
        output.write("width", width);
        output.write("height", height);
    }

    @Override
    public void read(InputCapsule input) {
        x = input.readFloat("x");
        y = input.readFloat("y");
        width = input.readFloat("width");
        height = input.readFloat("height");
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", x, y, width, height);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Rect other)) {
            return false;
        }
        return Float.compare(x, other.x) == 0
                && Float.compare(y, other.y) == 0
                && Float.compare(width, other.width) == 0
                && Float.compare(height, other.height) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(x);
        result = 31 * result + Float.hashCode(y);
        result = 31 * result + Float.hashCode(width);
        result = 31 * result + Float.hashCode(height);
        return result;
    }
}
