package cn.donting.javelin.core.render.material;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.ColorComponentFlags;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pass 固定渲染状态（.shader Pass 块内 Cull/ZWrite/ZTest/Blend/ColorMask 命令
 * 解析后的结构，RHI 21.3 节）。纯数据、device 无关；
 * 组装 GraphicsPipelineDesc 在渲染侧进行。
 *
 * <p>默认对齐 Unity 惯例：Cull Back / ZWrite On / ZTest LEqual / Blend Off / ColorMask RGBA。</p>
 *
 * <p>序列化：实现 {@link Serializable} 走 Capsule 二进制协议（.shader 资产产物格式，
 * Engine_Material_Design §3）；colorMask 存为位掩码 int，避免枚举集直接序列化。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class PassState implements Serializable {

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

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public PassState() {
    }

    /**
     * 字段级拷贝（colorMask 重建新集合，与本体无共享可变状态）：
     * 渲染侧按材质 {@link SurfaceType} 覆盖 Blend/depthWrite 后组装 PSO 用
     * （材质覆盖不回写 Shader 资产，拍板 2026-10-17 方案 B）。
     */
    public PassState copy() {
        PassState c = new PassState();
        c.cull = cull;
        c.depthWrite = depthWrite;
        c.depthTest = depthTest;
        c.blendEnabled = blendEnabled;
        c.srcColor = srcColor;
        c.dstColor = dstColor;
        c.srcAlpha = srcAlpha;
        c.dstAlpha = dstAlpha;
        c.blendOp = blendOp;
        c.colorMask = intToColorMask(colorMaskToInt(colorMask));
        return c;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("cull", cull);
        output.write("depthWrite", depthWrite);
        output.write("depthTest", depthTest);
        output.write("blendEnabled", blendEnabled);
        output.write("srcColor", srcColor);
        output.write("dstColor", dstColor);
        output.write("srcAlpha", srcAlpha);
        output.write("dstAlpha", dstAlpha);
        output.write("blendOp", blendOp);
        // 枚举集不直接走 writeEnumList（元素类需 @Reflective），改存位掩码 int
        output.write("colorMask", colorMaskToInt(colorMask));
    }

    @Override
    public void read(InputCapsule input) {
        CullMode c = input.readEnum("cull", CullMode.class);
        if (c != null) {
            cull = c;
        }
        depthWrite = input.readBoolean("depthWrite");
        CompareOp dt = input.readEnum("depthTest", CompareOp.class);
        if (dt != null) {
            depthTest = dt;
        }
        blendEnabled = input.readBoolean("blendEnabled");
        BlendFactor sc = input.readEnum("srcColor", BlendFactor.class);
        if (sc != null) {
            srcColor = sc;
        }
        BlendFactor dc = input.readEnum("dstColor", BlendFactor.class);
        if (dc != null) {
            dstColor = dc;
        }
        BlendFactor sa = input.readEnum("srcAlpha", BlendFactor.class);
        if (sa != null) {
            srcAlpha = sa;
        }
        BlendFactor da = input.readEnum("dstAlpha", BlendFactor.class);
        if (da != null) {
            dstAlpha = da;
        }
        BlendOp op = input.readEnum("blendOp", BlendOp.class);
        if (op != null) {
            blendOp = op;
        }
        colorMask = intToColorMask(input.readInt("colorMask"));
    }

    private static int colorMaskToInt(Set<ColorComponentFlags> mask) {
        int bits = 0;
        for (ColorComponentFlags f : mask) {
            bits |= 1 << f.ordinal();
        }
        return bits;
    }

    private static Set<ColorComponentFlags> intToColorMask(int bits) {
        Set<ColorComponentFlags> set = EnumSet.noneOf(ColorComponentFlags.class);
        for (ColorComponentFlags f : ColorComponentFlags.values()) {
            if ((bits & (1 << f.ordinal())) != 0) {
                set.add(f);
            }
        }
        return set;
    }

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
