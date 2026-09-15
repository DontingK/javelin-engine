package com.javelin.rhi;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;

public class GLFWSurface implements RHISurface {
    private final static Logger log = LoggerFactory.getLogger(GLFWSurface.class);
    private boolean initialized;
    private long handle;

    @Override
    public void creat() {
        if (initialized) {
            log.warn("GLFW window already created, skipping");
            return;
        }

        // 设置 GLFW 错误回调（全局，只设置一次）
        if (glfwSetErrorCallback(null) == null) {
            GLFWErrorCallback.createPrint(System.err).set();
        }

        // 初始化 GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // 配置窗口提示
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);        // 不创建 OpenGL 上下文（用于 Vulkan）
        GLFW.glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);             // 初始不可见
        GLFW.glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // 创建窗口
        handle = glfwCreateWindow(1080, 720, "GLFW VK", MemoryUtil.NULL, MemoryUtil.NULL);
        if (handle == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // 显示窗口
        glfwShowWindow(handle);

        // 设置 ESC 键关闭窗口
        glfwSetKeyCallback(handle, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
        });

        initialized = true;
        log.info("GLFW window created: {}x{}, title='{}'", 1080, 720, "GLFW VK");
    }

    @Override
    public int width() {
        return 1080;
    }

    @Override
    public int height() {
        return 720;
    }

    @Override
    public void onResize(OnResize callback) {

    }

    @Override
    public String[] getRequiredInstanceExtensions() {
        var glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            return new String[0];
        }
        String[] extensions = new String[glfwExtensions.limit()];
        for (int i = 0; i < glfwExtensions.limit(); i++) {
            extensions[i] = glfwExtensions.getStringUTF8(i);
        }
        return extensions;
    }

    @Override
    public long createVulkanSurface(VkInstance instance) {
        try (var stack = stackPush()) {
            LongBuffer surfacePtr = stack.mallocLong(1);
            int result = GLFWVulkan.glfwCreateWindowSurface(instance, handle, null, surfacePtr);
            if (result != VK10.VK_SUCCESS) {
                log.error("Failed to create GLFW window surface: {}", result);
                return 0;
            }
            log.info("GLFW Vulkan Surface created successfully");
            return surfacePtr.get(0);
        }
    }

    @Override
    public void close() {
        if (handle != MemoryUtil.NULL) {
            glfwDestroyWindow(handle);
            handle = MemoryUtil.NULL;
        }
        glfwTerminate();
        log.info("GLFW 已清理");
    }

    /** 获取 GLFW 窗口句柄（用于主循环中的事件轮询）。 */
    public long getHandle() {
        return handle;
    }
}
