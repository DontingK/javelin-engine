package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * SSAO 设置（Engine_SSAO_Design §3.6）：开关 + 采样半径 / 深度偏移 / 合成强度。
 *
 * <p><b>为何独立成对象</b>：SSAO 域内聚（开关与参数同居）——面板按它展开成「环境光遮蔽」
 * 分组，参数经 {@code PostProcessSetup} 下发到 SsaoPass（push 常量）。字段级缺键回退由
 * {@link InputCapsule} 的默认值重载负责（默认值 = 字段声明初值）。</p>
 *
 * <p><b>面板声明（2026-11-14）</b>：字段贴 {@link InsField}/{@link InsFloatRange}，
 * 设置面板按注解生成行；本对象即「环境光遮蔽」分组（开关随组走）。</p>
 */
@Reflective
public class SsaoSettings implements Serializable {

    /** 开关（独立于 Tonemap/Bloom；仅 HYBRID 档生效，FORWARD 档忽略并 warn 一次）默认关 */
    @InsField(label = "projectSettings.ssao")
    private boolean enabled = false;
    /** 采样半径（世界单位；核内样本按此缩放，越大遮蔽范围越广） */
    @InsField(label = "projectSettings.ssaoRadius")
    @InsFloatRange(min = 0.01f, max = 5f, step = 0.05f)
    private float radius = 0.5f;
    /** 视空间深度偏移（防表面自遮挡产生的暗斑/条纹） */
    @InsField(label = "projectSettings.ssaoBias")
    @InsFloatRange(min = 0f, max = 0.5f, step = 0.005f)
    private float bias = 0.02f;
    /** 合成强度（0 = 视觉等同关闭；1 = 全量 AO） */
    @InsField(label = "projectSettings.ssaoIntensity")
    @InsFloatRange(min = 0f, max = 2f, step = 0.05f)
    private float intensity = 1.0f;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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
        output.write("enabled", enabled);
        output.write("radius", radius);
        output.write("bias", bias);
        output.write("intensity", intensity);
    }

    @Override
    public void read(InputCapsule input) {
        // 默认值 = 字段声明初值：缺键即字段默认
        enabled = input.readBoolean("enabled", false);
        radius = input.readFloat("radius", 0.5f);
        bias = input.readFloat("bias", 0.02f);
        intensity = input.readFloat("intensity", 1.0f);
    }
}
