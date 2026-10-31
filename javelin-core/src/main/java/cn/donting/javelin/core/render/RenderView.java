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
    /** 本视口 LightingParamsBlock 在光照 UBO 帧槽内的动态偏移（绑 set1 binding0 时传入；批次②） */
    private final int lightingOffset;
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
        this.lightingOffset = 0;
        this.viewport = viewport;
        this.scissor = scissor;
        this.list = list;
        this.targetOverride = targetOverride;
        this.clearColor = clearColor;
        this.clearFlags = clearFlags;
    }

    /**
     * 全参构造（RenderingSystem.collectFrame 走此路——lightingOffset 与 uboOffset 同帧槽成对）。
     */
    public RenderView(String name, int uboOffset, int lightingOffset,
                      RHIViewport viewport, RHIScissor scissor, RenderList list,
                      Texture targetOverride, RHIClearColor clearColor, ClearFlags clearFlags) {
        this.name = name;
        this.uboOffset = uboOffset;
        this.lightingOffset = lightingOffset;
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

    /** 光照 UBO 槽内动态偏移（512B 对齐；绑 set1 binding0 时作为 dynamic offset 传入） */
    public int lightingOffset() {
        return lightingOffset;
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
