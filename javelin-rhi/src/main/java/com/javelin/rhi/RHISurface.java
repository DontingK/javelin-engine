package com.javelin.rhi;

import org.lwjgl.vulkan.VkInstance;

/**
 * 渲染表面：窗口系统的抽象，RHI 通过它创建 VkSurfaceKHR。
 * <p>
 * 由上层（引擎或窗口适配层）实现。RHI 只调用 {@link #createVulkanSurface} 获取 VkSurfaceKHR，
 * 后续的 Swapchain 创建、纹理获取、呈现全部由 RHI 内部处理。
 */
public interface RHISurface extends AutoCloseable {

    void creat();

    /** 渲染表面宽度（像素），对应 Swapchain 图像宽度。窗口 resize 后自动更新。 */
    int width();

    /** 渲染表面高度（像素），对应 Swapchain 图像高度。窗口 resize 后自动更新。 */
    int height();

    /** 注册窗口尺寸变化回调。 */
    void onResize(OnResize callback);
    /**
     * 获取 Vulkan Instance 所需的扩展名称列表。
     * <p>
     * 各平台需要不同的 Instance 扩展来创建 Surface：
     * <ul>
     *   <li><b>GLFW</b>：{@code VK_KHR_surface} + 平台特定（如 {@code VK_KHR_win32_surface}）</li>
     *   <li><b>Win32 + Swing</b>：{@code VK_KHR_surface} + {@code VK_KHR_win32_surface}</li>
     * </ul>
     *
     * @return 扩展名称数组
     */
    String[] getRequiredInstanceExtensions();

    /**
     * 创建 Vulkan Surface。
     * <p>
     * 由 RHI 在初始化时调用一次，实现类根据窗口类型调用对应平台 API：
     * <ul>
     *   <li>GLFW: {@code glfwCreateWindowSurface(instance, window, null, surfacePtr)}</li>
     *   <li>Swing/Win32: {@code vkCreateWin32SurfaceKHR(instance, createInfo, null, surfacePtr)}</li>
     * </ul>
     *
     * @param instance Vulkan 实例
     * @return VkSurfaceKHR 句柄
     */
    long createVulkanSurface(VkInstance instance);

    /** 销毁表面资源。调用后不可再用。 */
    @Override
    void close();

    @FunctionalInterface
    interface OnResize {
        /** 窗口尺寸变化时调用。 */
        void resize(int w, int h);
    }
}
