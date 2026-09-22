package com.javelin.core.window;

import com.javelin.rhi.render.Platform;
import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * GLFW 窗口的呈现面适配器：把窗口状态翻译成 RHI 消费的 {@link RenderSurface}
 * （RHI 设计文档 23.2 控制反转：上层实现、RHI 只认 nativeHandle + platform）。
 * 对照设计文档 doc/Engine_Window_Design.md。
 *
 * <p>resize 检测模型：每帧查询 {@link #isResized()}（内部轮询 framebuffer 尺寸并与
 * 上次确认值比较），渲染侧发现变化后做 swapchain recreate，再调 {@link #acknowledgeResize()}。
 * 初始确认值为 -1，首次查询必为 true——恰好驱动 swapchain 的首次创建。</p>
 */
final class GLFWRenderSurface implements RenderSurface {

    private final GLFWWindow window;
    private int acknowledgedWidth = -1;
    private int acknowledgedHeight = -1;

    GLFWRenderSurface(GLFWWindow window) {
        this.window = window;
    }

    @Override
    public long nativeHandle() {
        long glfwHandle = window.nativeHandle();
        // RHI 按平台把该值当原生窗口句柄使用（Win32 surface 创建需要真正的 HWND，
        // glfwCreateWindow 返回的是 GLFW 内部结构体指针，须经 native 访问器翻译）
        return platform() == Platform.WINDOWS
                ? GLFWNativeWin32.glfwGetWin32Window(glfwHandle)
                : glfwHandle;
    }

    @Override
    public Platform platform() {
        return switch (org.lwjgl.system.Platform.get()) {
            case WINDOWS -> Platform.WINDOWS;
            case MACOSX -> Platform.MACOS;
            case LINUX, FREEBSD -> Platform.X11;   // 简化：默认 X11，Wayland 区分留待 Linux 适配时做
        };
    }

    @Override
    public int width() {
        return window.framebufferSize()[0];
    }

    @Override
    public int height() {
        return window.framebufferSize()[1];
    }

    @Override
    public boolean isResized() {
        return width() != acknowledgedWidth || height() != acknowledgedHeight;
    }

    @Override
    public void acknowledgeResize() {
        acknowledgedWidth = width();
        acknowledgedHeight = height();
    }

    @Override
    public boolean isVisible() {
        return window.isVisible();
    }
}
