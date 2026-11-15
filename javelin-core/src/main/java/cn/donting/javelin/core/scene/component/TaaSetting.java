package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 相机 TAA 参数组（对齐 Unity HDRP「Anti-aliasing → TAA」四项）。
 *
 * <p>开关在 {@link Camera#getAntialiasing()}：选 {@code TAA} 时 Inspector 内联展开本组
 * （{@code @InsFieldGroup} 指向该字段）；参数只在本视口启用 TAA 时生效。</p>
 *
 * <p>默认值 = 等值于已验收行为：锐化 0.87（Babylon FSR1 `sharpnessStops=0.2` 的换算）、
 * 历史锐化 1.0（全 Catmull-Rom）、防闪烁 0（关）、速度抑制 1.0（0.5 纹素起降权）。</p>
 *
 * @author javelin
 * @create 2026/11/15
 **/
@Reflective
public class TaaSetting implements Serializable {

    /** 锐化强度（TAA 配套 RCAS，0 = 不锐化直出） */
    @InsField(label = "ins.camera.sharpenStrength")
    @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
    @InsSlider
    private float sharpenStrength = 0.87f;

    /** 历史锐化（0 = 双线性重采样，1 = 全 Catmull-Rom——抗时域累积低通） */
    @InsField(label = "ins.camera.historySharpening")
    @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
    @InsSlider
    private float historySharpening = 1.0f;

    /** 防闪烁（0~1，映射为 3×3 邻域 AABB 钳制强度 0~2；0 = 关） */
    @InsField(label = "ins.camera.antiFlickering")
    @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
    @InsSlider
    private float antiFlickering = 0.0f;

    /** 速度抑制（0~1，位移超阈值即降权；0 = 不抑制，1 = 0.5 纹素起降权） */
    @InsField(label = "ins.camera.speedRejection")
    @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
    @InsSlider
    private float speedRejection = 1.0f;

    public float getSharpenStrength() {
        return sharpenStrength;
    }

    public void setSharpenStrength(float sharpenStrength) {
        this.sharpenStrength = sharpenStrength;
    }

    public float getHistorySharpening() {
        return historySharpening;
    }

    public void setHistorySharpening(float historySharpening) {
        this.historySharpening = historySharpening;
    }

    public float getAntiFlickering() {
        return antiFlickering;
    }

    public void setAntiFlickering(float antiFlickering) {
        this.antiFlickering = antiFlickering;
    }

    public float getSpeedRejection() {
        return speedRejection;
    }

    public void setSpeedRejection(float speedRejection) {
        this.speedRejection = speedRejection;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("sharpenStrength", sharpenStrength);
        output.write("historySharpening", historySharpening);
        output.write("antiFlickering", antiFlickering);
        output.write("speedRejection", speedRejection);
    }

    @Override
    public void read(InputCapsule input) {
        // 缺键保字段初值（旧场景没有本组时的默认与已验收行为一致）
        sharpenStrength = input.readFloat("sharpenStrength", 0.87f);
        historySharpening = input.readFloat("historySharpening", 1.0f);
        antiFlickering = input.readFloat("antiFlickering", 0.0f);
        speedRejection = input.readFloat("speedRejection", 1.0f);
    }
}
