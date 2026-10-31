package cn.donting.javelin.core.render;

import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;

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
    /** 本视口清屏颜色（null = 用管线全局默认色） */
    private final RHIClearColor clearColor;
    /** 本视口清屏标志（SOLID_COLOR 才消费 clearColor） */
    private final ClearFlags clearFlags;

    public RenderView(String name, int uboOffset,
                      RHIViewport viewport, RHIScissor scissor, RenderList list) {
        this(name, uboOffset, viewport, scissor, list, null, null, ClearFlags.SOLID_COLOR);
    }

    public RenderView(String name, int uboOffset,
                      RHIViewport viewport, RHIScissor scissor, RenderList list,
                      Texture targetOverride, RHIClearColor clearColor, ClearFlags clearFlags) {
        this.name = name;
        this.uboOffset = uboOffset;
        this.viewport = viewport;
        this.scissor = scissor;
        this.list = list;
        this.targetOverride = targetOverride;
        this.clearColor = clearColor;
        this.clearFlags = clearFlags;
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

    /** 本视口清屏颜色（null = 用管线全局默认色） */
    public RHIClearColor clearColor() {
        return clearColor;
    }

    /** 本视口清屏标志（SOLID_COLOR 才消费 clearColor） */
    public ClearFlags clearFlags() {
        return clearFlags;
    }
}
