package com.javelin.rhi.api;

import com.javelin.rhi.vulkan.VulkanInstance;
import org.lwjgl.vulkan.VkInstance;

/**
 * RHI 窗口抽象接口，用于 Vulkan Surface 创建和平台扩展管理。
 * <p>
 * 不同平台（GLFW、Win32、X11、Wayland 等）实现此接口，RHI 内部通过此接口
 * 创建 Vulkan Surface 和获取平台所需的 Instance 扩展，无需关心具体窗口库。
 */
public interface RHIWindow {

    /**
     * 获取窗口宽度（像素）。
     */
    int width();

    /**
     * 获取窗口高度（像素）。
     */
    int height();

    /**
     * 创建 Vulkan Surface。
     *
     * @param instance Vulkan 实例句柄
     * @return Surface handle，失败返回 0
     */
    long createSurface(VkInstance instance);

    /**
     * 获取 Vulkan Instance 所需的扩展名称列表。
     * <p>
     * 各平台需要不同的扩展来创建 Surface：
     * <ul>
     *   <li>GLFW: VK_KHR_surface + VK_KHR_win32_surface / VK_KHR_xlib_surface / ...</li>
     *   <li>Win32: VK_KHR_surface + VK_KHR_win32_surface</li>
     * </ul>
     *
     * @return 扩展名称数组
     */
    String[] requiredInstanceExtensions();
}
