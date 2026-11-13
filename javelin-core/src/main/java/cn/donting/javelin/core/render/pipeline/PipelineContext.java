package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.render.FrameDescription;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.rhi.Texture;

/**
 * 管线构建上下文（Engine_RenderPipeline_Design.md 6.1）：buildGraph 的输入。
 *
 * <p>frame 为帧描述（多视口共享同一实例）；view 为本视口；depth 为本视口深度纹理
 * （视口独立，RHI 19.4-2）；终点附件 = targetOverride 非空 ? targetOverride : backbuffer。
 * hdrSceneTarget 为本视口 HDR 场景颜色（后处理链非空时非 null；场景 Pass 写它、
 * 链尾 Tonemap 写 colorTarget，见 {@link #sceneColorTarget()}）。</p>
 */
public final class PipelineContext {

    private final RenderingSystem renderingSystem;
    private final RenderView view;
    private final FrameDescription frame;
    private final Texture backbuffer;
    private final Texture depth;
    /** 本视口 HDR 场景颜色（后处理链非空时非 null，Engine_PostProcess_Design §3.2/3.3） */
    private final Texture hdrSceneTarget;

    public PipelineContext(RenderingSystem renderingSystem, RenderView view,
                           FrameDescription frame, Texture backbuffer, Texture depth,
                           Texture hdrSceneTarget) {
        this.renderingSystem = renderingSystem;
        this.view = view;
        this.frame = frame;
        this.backbuffer = backbuffer;
        this.depth = depth;
        this.hdrSceneTarget = hdrSceneTarget;
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

    /**
     * 场景颜色终点（Engine_PostProcess_Design §3.2）：HDR 生效（后处理链非空）时为本视口
     * lightingRT，否则同 {@link #colorTarget()}。四个写颜色的场景 Pass（Opaque/Lighting/
     * Skybox/Transparent）取本方法；Composite/Tonemap 取 {@link #colorTarget()}。
     */
    public Texture sceneColorTarget() {
        return hdrSceneTarget != null ? hdrSceneTarget : colorTarget();
    }

    /** HDR 是否生效（后处理链非空；场景色与显示终点分离） */
    public boolean hdrEnabled() {
        return hdrSceneTarget != null;
    }
}
