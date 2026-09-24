package com.javelin.core.render;

import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;

/**
 * 引擎侧视图（Engine_Rendering_Design.md 第 3 节）：每相机每帧一份。
 *
 * <p>帧级对象：由 RenderingSystem.collectFrame 每帧重建；
 * uboOffset = 本相机 viewProj 在 UBO1 帧槽内的动态偏移（绑 set0 时传入）。
 */
public final class RenderView {

    private final String name;
    private final int uboOffset;
    private final RHIViewport viewport;
    private final RHIScissor scissor;
    private final RenderList list;
    private final Texture targetOverride;

    public RenderView(String name, int uboOffset,
                      RHIViewport viewport, RHIScissor scissor, RenderList list) {
        this(name, uboOffset, viewport, scissor, list, null);
    }

    public RenderView(String name, int uboOffset,
                      RHIViewport viewport, RHIScissor scissor, RenderList list,
                      Texture targetOverride) {
        this.name = name;
        this.uboOffset = uboOffset;
        this.viewport = viewport;
        this.scissor = scissor;
        this.list = list;
        this.targetOverride = targetOverride;
    }

    public String name() {
        return name;
    }

    /** UBO1 槽内动态偏移（256B 对齐；绑 set0 时作为 dynamic offset 传入） */
    public int uboOffset() {
        return uboOffset;
    }

    public RHIViewport viewport() {
        return viewport;
    }

    public RHIScissor scissor() {
        return scissor;
    }

    public RenderList list() {
        return list;
    }

    /** 非空 = 渲染到纹理（编辑器）；null = 由帧描述 backbuffer 决定 */
    public Texture targetOverride() {
        return targetOverride;
    }
}
