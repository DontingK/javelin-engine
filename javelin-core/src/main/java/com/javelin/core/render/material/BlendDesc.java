package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 混合状态描述。
 *
 * <p>着色器 DSL {@code blend} 声明的导入产物：
 * {@code blend Off} → enabled=false（src=One, dst=Zero）；
 * {@code blend <src> <dst>} → enabled=true 并记录两个因子。</p>
 *
 * <p>{@link ShaderPass#getBlend()} 返回 null 表示 DSL 未显式声明，
 * 由消费方按 renderLayer 取默认（Opaque→Off，其余→alphaBlend）。</p>
 */
@Reflective
public class BlendDesc implements Serializable {

    /** 是否启用混合 */
    private boolean enabled;

    /** 源混合因子 */
    private BlendFactor srcFactor = BlendFactor.One;

    /** 目标混合因子 */
    private BlendFactor dstFactor = BlendFactor.Zero;

    public BlendDesc() {
    }

    public BlendDesc(boolean enabled, BlendFactor srcFactor, BlendFactor dstFactor) {
        this.enabled = enabled;
        this.srcFactor = srcFactor;
        this.dstFactor = dstFactor;
    }

    /** 关闭混合的便捷常量语义（enabled=false, One/Zero） */
    public static BlendDesc off() {
        return new BlendDesc(false, BlendFactor.One, BlendFactor.Zero);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BlendFactor getSrcFactor() {
        return srcFactor;
    }

    public void setSrcFactor(BlendFactor srcFactor) {
        this.srcFactor = srcFactor;
    }

    public BlendFactor getDstFactor() {
        return dstFactor;
    }

    public void setDstFactor(BlendFactor dstFactor) {
        this.dstFactor = dstFactor;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("enabled", enabled);
        output.write("srcFactor", srcFactor);
        output.write("dstFactor", dstFactor);
    }

    @Override
    public void read(InputCapsule input) {
        enabled = input.readBoolean("enabled");
        srcFactor = input.readEnum("srcFactor", BlendFactor.class);
        dstFactor = input.readEnum("dstFactor", BlendFactor.class);
    }
}
