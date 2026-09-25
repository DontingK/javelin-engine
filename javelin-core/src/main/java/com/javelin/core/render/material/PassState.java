package com.javelin.core.render.material;

import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.BlendOp;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;

import java.util.Set;

/**
 * Pass 固定渲染状态（.shader Pass 块内 Cull/ZWrite/ZTest/Blend/ColorMask 命令
 * 解析后的结构，RHI 21.3 节）。纯数据、device 无关；
 * 组装 GraphicsPipelineDesc 在渲染侧进行。
 *
 * <p>默认对齐 Unity 惯例：Cull Back / ZWrite On / ZTest LEqual / Blend Off / ColorMask RGBA。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class PassState {

    private CullMode cull = CullMode.BACK;
    private boolean depthWrite = true;
    private CompareOp depthTest = CompareOp.LESS_OR_EQUAL;
    private boolean blendEnabled = false;
    private BlendFactor srcColor = BlendFactor.ONE;
    private BlendFactor dstColor = BlendFactor.ZERO;
    private BlendFactor srcAlpha = BlendFactor.ONE;
    private BlendFactor dstAlpha = BlendFactor.ZERO;
    private BlendOp blendOp = BlendOp.ADD;
    private Set<ColorComponentFlags> colorMask = Set.of(
            ColorComponentFlags.R, ColorComponentFlags.G,
            ColorComponentFlags.B, ColorComponentFlags.A);

    public CullMode getCull() {
        return cull;
    }

    public PassState setCull(CullMode cull) {
        this.cull = cull;
        return this;
    }

    public boolean isDepthWrite() {
        return depthWrite;
    }

    public PassState setDepthWrite(boolean depthWrite) {
        this.depthWrite = depthWrite;
        return this;
    }

    public CompareOp getDepthTest() {
        return depthTest;
    }

    public PassState setDepthTest(CompareOp depthTest) {
        this.depthTest = depthTest;
        return this;
    }

    public boolean isBlendEnabled() {
        return blendEnabled;
    }

    public BlendFactor getSrcColor() {
        return srcColor;
    }

    public BlendFactor getDstColor() {
        return dstColor;
    }

    public BlendFactor getSrcAlpha() {
        return srcAlpha;
    }

    public BlendFactor getDstAlpha() {
        return dstAlpha;
    }

    public BlendOp getBlendOp() {
        return blendOp;
    }

    /**
     * 配置混合（Blend src dst，颜色/alpha 同因子；Blend Off 即不启用）。
     */
    public PassState setBlend(boolean enabled, BlendFactor src, BlendFactor dst) {
        this.blendEnabled = enabled;
        this.srcColor = src;
        this.dstColor = dst;
        this.srcAlpha = src;
        this.dstAlpha = dst;
        return this;
    }

    public Set<ColorComponentFlags> getColorMask() {
        return colorMask;
    }

    public PassState setColorMask(Set<ColorComponentFlags> colorMask) {
        this.colorMask = colorMask;
        return this;
    }
}
