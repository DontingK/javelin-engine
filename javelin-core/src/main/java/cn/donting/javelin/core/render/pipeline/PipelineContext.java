package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.render.FrameDescription;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.rhi.Texture;

/**
 * 管线构建上下文（Engine_RenderPipeline_Design.md 6.1）：buildGraph 的输入。
 *
 * <p>frame 为帧描述（多视口共享同一实例）；view 为本视口；
 * depth 为本视口深度纹理（视口独立，RHI 19.4-2，overlay 家族用，恒 1x）；
 * 终点附件 = targetOverride 非空 ? targetOverride : backbuffer。
 * msaaColor/msaaDepth 为本视口多采样附件（AA 开启时非 null；场景 Pass 走
 * MSAA + resolve，overlay 恒用 1x 终点/深度，见 isMsaaPath）。
 */
public final class PipelineContext {

    private final RenderingSystem renderingSystem;
    private final RenderView view;
    private final FrameDescription frame;
    private final Texture backbuffer;
    private final Texture depth;
    /** 本视口 MSAA 颜色附件（AA 关 = null；非 null 时 sampleCount > 1，resolve 回 colorTarget） */
    private final Texture msaaColor;
    /** 本视口 MSAA 深度附件（与 msaaColor 成对；采样数一致性由两者同档保证） */
    private final Texture msaaDepth;

    public PipelineContext(RenderingSystem renderingSystem, RenderView view,
                           FrameDescription frame, Texture backbuffer, Texture depth,
                           Texture msaaColor, Texture msaaDepth) {
        this.renderingSystem = renderingSystem;
        this.view = view;
        this.frame = frame;
        this.backbuffer = backbuffer;
        this.depth = depth;
        this.msaaColor = msaaColor;
        this.msaaDepth = msaaDepth;
    }

    public RenderingSystem renderingSystem() {
        return renderingSystem;
    }

    public RenderView view() {
        return view;
    }

    public FrameDescription frame() {
        return frame;
    }

    public Texture backbuffer() {
        return backbuffer;
    }

    public Texture depth() {
        return depth;
    }

    /** 本视口 MSAA 颜色附件（null = AA 关，走 1x 直写） */
    public Texture msaaColor() {
        return msaaColor;
    }

    /** 本视口 MSAA 深度附件（null = AA 关） */
    public Texture msaaDepth() {
        return msaaDepth;
    }

    /** 本视口颜色终点 */
    public Texture colorTarget() {
        return view.targetOverride() != null ? view.targetOverride() : backbuffer;
    }
}
