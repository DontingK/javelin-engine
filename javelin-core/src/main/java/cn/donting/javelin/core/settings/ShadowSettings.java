package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsIntRange;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 阴影参数（Engine_Shadow_Quality_Design §3.2/§3.5）：品质档 / 强度 / PCF 半径 /
 * 参考深度偏移 / PSO 静态 depth bias 两项 / CSM 级联三项。
 *
 * <p><b>为何独立成对象</b>：阴影参数域内聚——面板按它展开成「阴影」分组，引擎侧按它整体
 * 取用。字段级缺键回退由 {@link InputCapsule} 的默认值重载负责（默认值 = 字段声明初值），
 * 嵌套对象自身的缺键回退走 {@code readSerializable} 的 null 判定。</p>
 *
 * <p><b>生效路径</b>：品质档/强度/PCF 半径/参考深度偏移每帧打包进 set1 UBO
 * （{@code LightingParamsBlock.shadowParams}），改后即时生效；bias 两项是 PSO 静态态，
 * 改动触发 {@code ShadowPass} 重建 PSO 缓存（下一帧起生效）。</p>
 *
 * <p><b>面板声明（2026-11-14）</b>：字段贴 {@link InsField}/{@link InsFloatRange}/
 * {@link InsIntRange}，设置面板按注解生成行（级联数为三档下拉，走设置 schema 的显式项）。</p>
 */
@Reflective
public class ShadowSettings implements Serializable {

    /** 采样品质档（缺省软影低档） */
    @InsField(label = "projectSettings.shadowQuality")
    private ShadowQuality quality = ShadowQuality.SOFT_LOW;
    /** 阴影强度 0~1（1 = 原强度；0 = 视觉等同关影） */
    @InsField(label = "projectSettings.shadowStrength")
    @InsFloatRange(min = 0f, max = 1f, step = 0.05f)
    private float strength = 1.0f;
    /** PCF 半径（纹素；核偏移步长缩放，越大半影越宽） */
    @InsField(label = "projectSettings.shadowPcfRadius")
    @InsFloatRange(min = 0f, max = 4f, step = 0.1f)
    private float pcfRadius = 1.0f;
    /** 参考深度偏移（世界单位；正交光空间由渲染侧折算成 z 增量）——抗彼得潘宁的细调项 */
    @InsField(label = "projectSettings.shadowDepthOffset")
    @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
    private float refDepthOffset = 0.0f;
    /** PSO 静态 depth bias 常量项（抗自遮挡 acne；批次④-A 起步值 2.0） */
    @InsField(label = "projectSettings.shadowBiasConstant")
    @InsFloatRange(min = 0f, max = 10f, step = 0.1f)
    private float biasConstant = 2.0f;
    /** PSO 静态 depth bias 斜率项（随面斜率增大，批次④-A 起步值 1.5） */
    @InsField(label = "projectSettings.shadowBiasSlope")
    @InsFloatRange(min = 0f, max = 10f, step = 0.1f)
    @SettingHint("projectSettings.shadowBiasHint")
    private float biasSlope = 1.5f;
    /** CSM 级联数（1/2/4；1 = 退化为单张，与步骤一形态同源。缺省 2） */
    @InsField(label = "projectSettings.shadowCascades")
    @InsIntRange(min = 1, max = 4, step = 1)
    @SettingSelect(values = {1, 2, 4}, labelPrefix = "projectSettings.shadowCascades")
    private int cascadeCount = 2;
    /** 阴影距离（世界单位；超出恒受光。缺省 100） */
    @InsField(label = "projectSettings.shadowDistance")
    @InsFloatRange(min = 5f, max = 1000f, step = 5f)
    private float shadowDistance = 100.0f;
    /** 级联切分 lambda 混合系数（0=均匀、1=对数；缺省 0.5） */
    @InsField(label = "projectSettings.shadowCascadeLambda")
    @InsFloatRange(min = 0f, max = 1f, step = 0.05f)
    @SettingHint("projectSettings.shadowCascadeHint")
    private float cascadeLambda = 0.5f;

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

    public int getCascadeCount() {
        return cascadeCount;
    }

    /** 级联数仅接受 1/2/4（其余忽略，保持现值） */
    public void setCascadeCount(int cascadeCount) {
        if (cascadeCount == 1 || cascadeCount == 2 || cascadeCount == 4) {
            this.cascadeCount = cascadeCount;
        }
    }

    public float getShadowDistance() {
        return shadowDistance;
    }

    public void setShadowDistance(float shadowDistance) {
        this.shadowDistance = Math.max(shadowDistance, 0.1f);
    }

    public float getCascadeLambda() {
        return cascadeLambda;
    }

    public void setCascadeLambda(float cascadeLambda) {
        this.cascadeLambda = Math.min(Math.max(cascadeLambda, 0.0f), 1.0f);
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("quality", quality);
        output.write("strength", strength);
        output.write("pcfRadius", pcfRadius);
        output.write("refDepthOffset", refDepthOffset);
        output.write("biasConstant", biasConstant);
        output.write("biasSlope", biasSlope);
        output.write("cascadeCount", cascadeCount);
        output.write("shadowDistance", shadowDistance);
        output.write("cascadeLambda", cascadeLambda);
    }

    @Override
    public void read(InputCapsule input) {
        // 默认值 = 字段声明初值：缺键即字段默认（原「缺键读 0」的合法性守卫随之退役——
        // 越界值只可能来自手工改文件，钳制是静默兜底，如实读出是既定口径）
        quality = input.readEnum("quality", ShadowQuality.class, ShadowQuality.SOFT_LOW);
        strength = input.readFloat("strength", 1.0f);
        pcfRadius = input.readFloat("pcfRadius", 1.0f);
        refDepthOffset = input.readFloat("refDepthOffset", 0.0f);
        biasConstant = input.readFloat("biasConstant", 2.0f);
        biasSlope = input.readFloat("biasSlope", 1.5f);
        cascadeCount = input.readInt("cascadeCount", 2);
        shadowDistance = input.readFloat("shadowDistance", 100.0f);
        cascadeLambda = input.readFloat("cascadeLambda", 0.5f);
    }
}
