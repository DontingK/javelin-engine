package com.javelin.rhi;

import com.javelin.rhi.render.RenderSurface;

/**
 * Vulkan 实例接口。
 * 对照设计文档第 26.1 节。
 */
public interface Instance extends GPUResource {

    /**
     * 选择物理设备并创建逻辑设备。
     *
     * @param prefs   设备偏好
     * @param surface 呈现目标（用于检查 present 队列族支持）
     * @return Device
     */
    Device createDevice(RHIDevicePrefs prefs, RenderSurface surface);

    /**
     * 从上层呈现目标创建 Surface。
     *
     * @param renderSurface 上层呈现目标
     * @return Surface
     */
    Surface createSurface(RenderSurface renderSurface);

    /** Vulkan 句柄（RHI 内部使用） */
    long handle();
}
