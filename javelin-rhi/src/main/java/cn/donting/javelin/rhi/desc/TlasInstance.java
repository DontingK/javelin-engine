package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.AccelerationStructure;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * TLAS 实例（P4-06 B2b）：一条 = 「世界变换 + 一个 BLAS + 自定义索引」。
 *
 * <p>对象可复用（每帧只改 {@link #transform(Matrix4fc)}，不新建），TLAS 重建时按列表顺序
 * 写入实例缓冲；自定义索引在 B3 里映射回 `RenderObject`。</p>
 */
public final class TlasInstance {

    /** 实例记录的字节数（`VkAccelerationStructureInstanceKHR` 固定 64B） */
    public static final int BYTES = 64;

    private final AccelerationStructure accelerationStructure;
    private final Matrix4f transform = new Matrix4f();
    private int customIndex;
    private int mask = 0xFF;
    private boolean forceOpaque = true;

    public TlasInstance(AccelerationStructure accelerationStructure) {
        if (accelerationStructure == null) {
            throw new IllegalArgumentException("TLAS 实例需要 BLAS");
        }
        this.accelerationStructure = accelerationStructure;
    }

    /** 世界变换（取 3×4 部分；w 行由 Vulkan 侧补） */
    public TlasInstance transform(Matrix4fc world) {
        transform.set(world);
        return this;
    }

    /** 实例自定义索引（着色器 `gl_InstanceCustomIndexEXT` 读到；B3 用来反查对象） */
    public TlasInstance customIndex(int index) {
        this.customIndex = index;
        return this;
    }

    /** 射线掩码（低 8 位；默认 0xFF 全命中） */
    public TlasInstance mask(int mask) {
        this.mask = mask & 0xFF;
        return this;
    }

    /**
     * 是否强制不透明（默认 true）。透明/带 alpha 裁剪的网格置 false —— 求交结果是否忽略
     * alpha 由消费方决定（ray query 侧没有 alpha 测试，B3 先用不透明口径）。
     */
    public TlasInstance forceOpaque(boolean opaque) {
        this.forceOpaque = opaque;
        return this;
    }

    public Matrix4fc transform() { return transform; }

    public long blasAddress() { return accelerationStructure.deviceAddress(); }

    public int customIndex() { return customIndex; }

    public int mask() { return mask; }

    public boolean isForceOpaque() { return forceOpaque; }
}
