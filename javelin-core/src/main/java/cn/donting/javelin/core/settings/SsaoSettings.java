package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * SSAO 参数（Engine_SSAO_Design §3.6）：采样半径 / 深度偏移 / 合成强度。
 *
 * <p><b>为何独立成对象</b>：{@link InputCapsule} 无「键是否存在」判定、浮点缺键恒 0——
 * 参数后补进 {@link PostProcessSettings} 时，旧设置文件会把这些键读成 0（半径 0 = 无 AO，
 * 非「保默认」），故按同级先例用嵌套 Serializable 对象，经 {@code readSerializable} 的
 * null 判定保默认。参数经 {@code PostProcessSetup} 下发到 SsaoPass（push 常量）。</p>
 */
@Reflective
public class SsaoSettings implements Serializable {

    /** 采样半径（世界单位；核内样本按此缩放，越大遮蔽范围越广） */
    private float radius = 0.5f;
    /** 视空间深度偏移（防表面自遮挡产生的暗斑/条纹） */
    private float bias = 0.02f;
    /** 合成强度（0 = 视觉等同关闭；1 = 全量 AO） */
    private float intensity = 1.0f;

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    public float getBias() {
        return bias;
    }

    public void setBias(float bias) {
        this.bias = bias;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("radius", radius);
        output.write("bias", bias);
        output.write("intensity", intensity);
    }

    @Override
    public void read(InputCapsule input) {
        // 本对象首次落地即含全部字段；后续新增字段时旧文件读 0（需另加「键存在」判定，见类注释）
        radius = input.readFloat("radius");
        bias = input.readFloat("bias");
        intensity = input.readFloat("intensity");
    }
}
