package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsIntRange;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 阴影参数（Engine_Shadow_Quality_Design §3.2/§3.5）：品质档 / 强度 / PCF 半径（PCSS 下为核半径
 * 下限）/ PCSS 软化系数 / 参考深度偏移 / PSO 静态 depth bias 两项 / CSM 级联三项。
 *
 * <p><b>为何独立成对象</b>：阴影参数域内聚——面板按它展开成「阴影」分组，引擎侧按它整体
 * 取用。字段级缺键回退由 {@link InputCapsule} 的默认值重载负责（默认值 = 字段声明初值），
 * 嵌套对象自身的缺键回退走 {@code readSerializable} 的 null 判定。</p>
 *
 * <p><b>生效路径</b>：品质档/强度/PCF 半径/软化系数/参考深度偏移每帧打包进 set1 UBO
 * （{@code LightingParamsBlock.shadowParams/shadowParams2}），改后即时生效；bias 两项是 PSO 静态态，
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
    @InsSlider
    private float strength = 1.0f;
    /** PCF 半径（纹素；核偏移步长缩放，越大半影越宽）——2026-11-20 接触漏光修复：缺省 1.0→0.6
     * （3×3 核在 2048 级联下有效半影可达 3 纹素 ≈ 10cm，接触处必漏；收窄既降漏光也保软影观感） */
    @InsField(label = "projectSettings.shadowPcfRadius")
    @InsFloatRange(min = 0f, max = 4f, step = 0.1f)
    @InsSlider
    private float pcfRadius = 0.6f;
    /** 参考深度偏移（世界单位；正交光空间由渲染侧折算成 z 增量）——抗彼得潘宁的细调项。
     * 2026-11-20 接触漏光修复：缺省 0→0.02（正值 = 阴影边界外扩，堵接触处漏光；过大则受影面
     * 出现 acne 暗斑，与 biasSlope 成对调） */
    @InsField(label = "projectSettings.shadowDepthOffset")
    @InsFloatRange(min = 0f, max = 1f, step = 0.01f)
    @InsSlider
    private float refDepthOffset = 0.02f;
    /** PSO 静态 depth bias 常量项（抗自遮挡 acne；批次④-A 起步值 2.0） */
    @InsField(label = "projectSettings.shadowBiasConstant")
    @InsFloatRange(min = 0f, max = 10f, step = 0.1f)
    @InsSlider
    private float biasConstant = 2.0f;
    /** PSO 静态 depth bias 斜率项（随面斜率增大）。2026-11-20：缺省 1.5→0.6 —— 1.5 在掠射面
     * （地面/大平台）把 caster 深度往远处推的量可达数厘米，正是接触处漏光的直接来源；
     * 降档后阴影边界回收，抗 acne 交给注入段固定的半纹素外扩 + normalOffset 方案 */
    @InsField(label = "projectSettings.shadowBiasSlope")
    @InsFloatRange(min = 0f, max = 10f, step = 0.1f)
    @InsSlider
    @SettingHint("projectSettings.shadowBiasHint")
    private float biasSlope = 0.6f;
    /** CSM 级联数（1/2/4；1 = 退化为单张，与步骤一形态同源）。2026-11-20：缺省 2→4 ——
     * 级联盒按相机视锥球拟合，近级联纹素尺寸近似与切分距离成正比：2 级联/100 距离时近级联
     * 单纹素约 3.3cm（PCF 核叠加后接触亮线可达 10cm），4 级联/80 距离降到约 1.3cm，且 atlas
     * 仍为 2×2 全用（显存不变）。代价 = caster 每级联全量重绘（v1 无逐级联剔除），绘制量 ×4 */
    @InsField(label = "projectSettings.shadowCascades")
    @InsIntRange(min = 1, max = 4, step = 1)
    @SettingSelect(values = {1, 2, 4}, labelPrefix = "projectSettings.shadowCascades")
    private int cascadeCount = 4;
    /** 阴影距离（世界单位；超出恒受光）。2026-11-20：缺省 100→80，近端两级联切分更细 */
    @InsField(label = "projectSettings.shadowDistance")
    @InsFloatRange(min = 5f, max = 1000f, step = 5f)
    @InsSlider
    private float shadowDistance = 80.0f;
    /** 级联切分 lambda 混合系数（0=均匀、1=对数；缺省 0.5） */
    @InsField(label = "projectSettings.shadowCascadeLambda")
    @InsFloatRange(min = 0f, max = 1f, step = 0.05f)
    @InsSlider
    @SettingHint("projectSettings.shadowCascadeHint")
    private float cascadeLambda = 0.5f;
    /**
     * PCSS 软化系数（REND-21-C1，2026-11-25）：0 = 关 PCSS（退回固定核 PCF，与改前逐像素
     * 一致）；&gt; 0 时做阻挡搜索，半影宽度随「接收面与遮挡体的光空间深度差」变化。
     * 缺省 1.0（软影档下近处约 1~2 纹素、分隔 1m 量级的遮挡约 3~4 纹素）。
     */
    @InsField(label = "projectSettings.shadowSoftness")
    @InsFloatRange(min = 0f, max = 4f, step = 0.05f)
    @InsSlider
    @SettingHint("projectSettings.shadowSoftnessHint")
    private float softness = 1.0f;

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

    public float getSoftness() {
        return softness;
    }

    /** 软化系数下限 0（0 = 关 PCSS，走固定核 PCF） */
    public void setSoftness(float softness) {
        this.softness = Math.max(softness, 0.0f);
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
        output.write("softness", softness);
    }

    @Override
    public void read(InputCapsule input) {
        // 默认值 = 字段声明初值：缺键即字段默认（原「缺键读 0」的合法性守卫随之退役——
        // 越界值只可能来自手工改文件，钳制是静默兜底，如实读出是既定口径）
        quality = input.readEnum("quality", ShadowQuality.class, ShadowQuality.SOFT_LOW);
        strength = input.readFloat("strength", 1.0f);
        pcfRadius = input.readFloat("pcfRadius", 0.6f);
        refDepthOffset = input.readFloat("refDepthOffset", 0.02f);
        biasConstant = input.readFloat("biasConstant", 2.0f);
        biasSlope = input.readFloat("biasSlope", 0.6f);
        cascadeCount = input.readInt("cascadeCount", 4);
        shadowDistance = input.readFloat("shadowDistance", 80.0f);
        cascadeLambda = input.readFloat("cascadeLambda", 0.5f);
        softness = input.readFloat("softness", 1.0f);
    }
}
