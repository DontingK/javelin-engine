package cn.donting.javelin.core.render;

import cn.donting.javelin.rhi.Texture;

import java.util.List;

/**
 * 帧描述（Engine_Rendering_Design.md 第 5 节；RHI 27.2 交接契约的字段级落地）。
 *
 * <p>数据不是执行：图声明阶段不碰 GPU，可安全跨线程传递（v0.1 单线程退化同构）。
 * 相机集合变化属结构变化（下游图缓存 structureHash 失效，RHI 18.5）；
 * 矩阵/时间只改数据。lights v0.1 恒为空表（Light 组件未实现）。
 */
public final class FrameDescription {

    private final long frameIndex;
    private final Texture backbuffer;
    private final List<RenderView> views;
    private final float timeSec;
    private final float tpf;
    private final int uboSlotBase;
    private final int cameraStride;

    public FrameDescription(long frameIndex, Texture backbuffer, List<RenderView> views,
                            float timeSec, float tpf, int uboSlotBase, int cameraStride) {
        this.frameIndex = frameIndex;
        this.backbuffer = backbuffer;
        this.views = List.copyOf(views);
        this.timeSec = timeSec;
        this.tpf = tpf;
        this.uboSlotBase = uboSlotBase;
        this.cameraStride = cameraStride;
    }

    public long frameIndex() {
        return frameIndex;
    }

    public Texture backbuffer() {
        return backbuffer;
    }

    public List<RenderView> views() {
        return views;
    }

    public float timeSec() {
        return timeSec;
    }

    public float tpf() {
        return tpf;
    }

    /** 相机序 → UBO1 动态偏移（槽基址 + 序 × 相机步长，均 256B 对齐） */
    public int frameOffset(int cameraIndex) {
        return uboSlotBase + cameraIndex * cameraStride;
    }
}
