package cn.donting.javelin.rhi;

/**
 * 顶层加速结构（TLAS，P4-06 B2b）：实例列表的聚合，**每帧重建**。
 *
 * <p>容量在创建时固定（`capacity` = 最大实例数）；{@link #build} 写实例缓冲并记录一次
 * `vkCmdBuildAccelerationStructuresKHR` + 构建后屏障。实例写入是 host-visible 直写，
 * 调用方须保证上一帧 GPU 已读完该缓冲（B3 之前按「每帧开头、上一帧已 fence 等」用）。</p>
 */
public interface Tlas extends GPUResource {

    /** 加速结构设备地址（着色器 `accelerationStructureEXT` 描述符） */
    long deviceAddress();

    /** 最大实例数 */
    int capacity();

    /** {@code VkAccelerationStructureKHR} 句柄 */
    long handle();

    /**
     * 写实例数据并记录 TLAS 重建。
     *
     * @param cmd       处于录制态的命令缓冲
     * @param instances 实例列表（不得超过 {@link #capacity()}）
     */
    void build(CommandBuffer cmd, java.util.List<cn.donting.javelin.rhi.desc.TlasInstance> instances);
}
