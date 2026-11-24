package cn.donting.javelin.rhi;

/**
 * 光线追踪加速结构（P4-06 B2）。
 *
 * <p>遮盖 BLAS / TLAS 两种形态：引擎侧拿到的是「可直接塞进着色器描述符的设备地址」，
 * 不感知底层的 {@code VkAccelerationStructureKHR} 与存储缓冲。</p>
 */
public interface AccelerationStructure extends GPUResource {

    /** {@code VkAccelerationStructureKHR} 句柄（构建 TLAS 作为实例引用时用） */
    long handle();

    /**
     * 加速结构设备地址（{@code vkGetAccelerationStructureDeviceAddress}）：着色器侧
     * {@code accelerationStructureEXT} 描述符就是它。
     */
    long deviceAddress();

    /** 背后存储缓冲字节数（统计/日志用） */
    long size();
}
