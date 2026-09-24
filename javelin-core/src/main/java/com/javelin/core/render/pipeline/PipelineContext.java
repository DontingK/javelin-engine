package com.javelin.core.render.pipeline;

import com.javelin.core.render.FrameDescription;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.rhi.Texture;

/**
 * 管线构建上下文（Engine_RenderPipeline_Design.md 6.1）：buildGraph 的输入。
 *
 * <p>frame 为帧描述（多视口共享同一实例）；view 为本视口；
 * depth 为本视口深度纹理（视口独立，RHI 19.4-2）；
 * 终点附件 = targetOverride 非空 ? targetOverride : backbuffer。
 */
public final class PipelineContext {

    private final RenderingSystem renderingSystem;
    private final RenderView view;
    private final FrameDescription frame;
    private final Texture backbuffer;
    private final Texture depth;

    public PipelineContext(RenderingSystem renderingSystem, RenderView view,
                           FrameDescription frame, Texture backbuffer, Texture depth) {
        this.renderingSystem = renderingSystem;
        this.view = view;
        this.frame = frame;
        this.backbuffer = backbuffer;
        this.depth = depth;
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

    /** 本视口颜色终点 */
    public Texture colorTarget() {
        return view.targetOverride() != null ? view.targetOverride() : backbuffer;
    }
}
