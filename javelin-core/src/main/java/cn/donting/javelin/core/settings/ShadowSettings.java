package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 阴影参数（Engine_Shadow_Quality_Design §3.2/§3.5）：品质档 / 强度 / PCF 半径 /
 * 参考深度偏移 / PSO 静态 depth bias 两项。
 *
 * <p><b>为何独立成对象</b>：{@link InputCapsule} 无「键是否存在」判定、浮点缺键恒 0，
 * 在 {@link JavelinSetting} 里直接加字段会让旧设置文件把浮点读成 0（强度 0 = 无阴影、
 * 半径 0 = 无软核，非「保默认」）——故按 {@code SsaoSettings} 同级先例用嵌套 Serializable
 * 对象，经 {@code readSerializable} 的 null 判定保默认。</p>
 *
 * <p><b>生效路径</b>：品质档/强度/PCF 半径/参考深度偏移每帧打包进 set1 UBO
 * （{@code LightingParamsBlock.shadowParams}），改后即时生效；bias 两项是 PSO 静态态，
 * 改动触发 {@code ShadowPass} 重建 PSO 缓存（下一帧起生效）。</p>
 */
@Reflective
public class ShadowSettings implements Serializable {

    /** 采样品质档（缺省软影低档） */
    private ShadowQuality quality = ShadowQuality.SOFT_LOW;
    /** 阴影强度 0~1（1 = 原强度；0 = 视觉等同关影） */
    private float strength = 1.0f;
    /** PCF 半径（纹素；核偏移步长缩放，越大半影越宽） */
    private float pcfRadius = 1.0f;
    /** 参考深度偏移（世界单位；正交光空间由渲染侧折算成 z 增量）——抗彼得潘宁的细调项 */
    private float refDepthOffset = 0.0f;
    /** PSO 静态 depth bias 常量项（抗自遮挡 acne；批次④-A 起步值 2.0） */
    private float biasConstant = 2.0f;
    /** PSO 静态 depth bias 斜率项（随面斜率增大，批次④-A 起步值 1.5） */
    private float biasSlope = 1.5f;

    public ShadowQuality getQuality() {
        return quality;
    }

    public void setQuality(ShadowQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        this.quality = quality;
    }

    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = Math.min(Math.max(strength, 0.0f), 1.0f);
    }

    public float getPcfRadius() {
        return pcfRadius;
    }

    public void setPcfRadius(float pcfRadius) {
        this.pcfRadius = Math.max(pcfRadius, 0.0f);
    }

    public float getRefDepthOffset() {
        return refDepthOffset;
    }

    public void setRefDepthOffset(float refDepthOffset) {
        this.refDepthOffset = refDepthOffset;
    }

    public float getBiasConstant() {
        return biasConstant;
    }

    public void setBiasConstant(float biasConstant) {
        this.biasConstant = Math.max(biasConstant, 0.0f);
    }

    public float getBiasSlope() {
        return biasSlope;
    }

    public void setBiasSlope(float biasSlope) {
        this.biasSlope = Math.max(biasSlope, 0.0f);
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("quality", quality);
        output.write("strength", strength);
        output.write("pcfRadius", pcfRadius);
        output.write("refDepthOffset", refDepthOffset);
        output.write("biasConstant", biasConstant);
        output.write("biasSlope", biasSlope);
    }

    @Override
    public void read(InputCapsule input) {
        // 本对象首次落地即含全部字段；后续新增字段时旧文件读 0（需另加「键存在」判定，见类注释）
        ShadowQuality q = input.readEnum("quality", ShadowQuality.class);
        if (q != null) {
            quality = q;
        }
        strength = input.readFloat("strength");
        pcfRadius = input.readFloat("pcfRadius");
        refDepthOffset = input.readFloat("refDepthOffset");
        biasConstant = input.readFloat("biasConstant");
        biasSlope = input.readFloat("biasSlope");
    }
}
