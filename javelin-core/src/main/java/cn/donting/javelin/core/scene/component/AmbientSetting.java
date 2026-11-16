package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 常量环境光参数组（{@code AmbientMode.CONSTANT} 生效项）。
 *
 * <p>Inspector 内经 {@code @InsFieldGroup} 在 {@code Environment.ambientMode} 选
 * {@code CONSTANT} 时内联展开：颜色 + 强度。颜色按线性值直接进
 * {@code LightingParamsBlock.ambient} 的 rgb、强度进 a（着色端 {@code ambient.rgb * ambient.a}）。</p>
 *
 * @author javelin
 * @create 2026/11/16
 **/
@Reflective
public class AmbientSetting implements Serializable {

    /** 环境光颜色（线性，无方向；默认中性灰） */
    @InsField(label = "ins.environment.ambientColor")
    private Color color = new Color(0.5f, 0.5f, 0.5f, 1.0f);

    /** 环境光强度（乘在颜色上；0 = 等同关闭） */
    @InsField(label = "ins.environment.ambientIntensity")
    @InsFloatRange(min = 0f, max = 8f, step = 0.01f)
    @InsSlider
    private float intensity = 1.0f;

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color != null ? color : new Color(0.5f, 0.5f, 0.5f, 1.0f);
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("color", color);
        output.write("intensity", intensity);
    }

    @Override
    public void read(InputCapsule input) {
        // 缺键保字段初值
        Color c = input.readSerializable("color", Color.class);
        if (c != null) {
            color = c;
        }
        intensity = input.readFloat("intensity", 1.0f);
    }
}
