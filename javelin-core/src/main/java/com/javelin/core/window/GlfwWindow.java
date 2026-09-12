package com.javelin.core.window;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * 基于 GLFW 的窗口实现，支持 Vulkan Surface 创建和窗口事件处理。
 * <p>
 * GLFW 回调在独立线程执行，所有回调事件先缓存到事件队列，
 * 由 {@link #pollEvent()} 在主线程统一消费，避免多线程竞争问题。
 */
public class GlfwWindow extends Window {

    private static final Logger log = LoggerFactory.getLogger(GlfwWindow.class);
    private static boolean glfwInitialized = false;

    private long windowHandle = NULL;
    private GLFWWindowSizeCallback sizeCallback;

    // 事件队列：GLFW 回调线程 -> 主线程
    private final ConcurrentLinkedQueue<Runnable> eventQueue = new ConcurrentLinkedQueue<>();

    public GlfwWindow(int width, int height, String title, boolean resizable) {
        this.width = width;
        this.height = height;
        this.title = title;
        this.resizable = resizable;
        this.shouldClose = false;
    }

    public GlfwWindow(int width, int height, String title) {
        this(width, height, title, true);
    }

    @Override
    public void create() {
        initGlfw();

        // 配置窗口属性
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);  // 不使用 OpenGL
        glfwWindowHint(GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);       // 先隐藏，show() 时再显示

        windowHandle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (windowHandle == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // 注册尺寸变化回调：缓存到事件队列，由 pollEvent 在主线程消费
        sizeCallback = new GLFWWindowSizeCallback() {
            @Override
            public void invoke(long window, int newWidth, int newHeight) {
                if (window == windowHandle) {
                    eventQueue.offer(() -> onResize(newWidth, newHeight,1));
                }
            }
        };
        glfwSetWindowSizeCallback(windowHandle, sizeCallback);

        // 注册关闭回调：缓存到事件队列，由 pollEvent 在主线程消费
        glfwSetWindowCloseCallback(windowHandle, (window) -> {
            if (window == windowHandle) {
                eventQueue.offer(() -> shouldClose = true);
            }
        });

        log.info("GLFW window created: {}x{} title='{}'", width, height, title);
    }

    @Override
    public void show() {
        if (windowHandle == NULL) {
            throw new IllegalStateException("Window not created, call create() first");
        }
        glfwShowWindow(windowHandle);
    }

    @Override
    public void hide() {
        if (windowHandle == NULL) {
            return;
        }
        glfwHideWindow(windowHandle);
    }

    @Override
    public void pollEvent() {
        if (windowHandle == NULL) {
            return;
        }
        glfwPollEvents();

        // 消费事件队列中的所有事件（在主线程执行）
        Runnable event;
        while ((event = eventQueue.poll()) != null) {
            try {
                event.run();
            } catch (Exception e) {
                log.error("Event queue task failed", e);
            }
        }

        shouldClose = shouldClose || glfwWindowShouldClose(windowHandle);
    }

    @Override
    public boolean shouldClose() {
        if (windowHandle == NULL) {
            return shouldClose;
        }
        return shouldClose || glfwWindowShouldClose(windowHandle);
    }

    @Override
    public void requestClose() {
        this.shouldClose = true;
        if (windowHandle != NULL) {
            glfwSetWindowShouldClose(windowHandle, true);
        }
    }

    @Override
    public void setTitle(String title) {
        this.title = title;
        if (windowHandle != NULL) {
            glfwSetWindowTitle(windowHandle, title);
        }
    }

    /**
     * 获取 GLFW 窗口句柄。
     */
    public long windowHandle() {
        return windowHandle;
    }

    @Override
    public long createSurface(VkInstance instance) {
        if (windowHandle == NULL) {
            throw new IllegalStateException("Window not created, call create() first");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            int result = GLFWVulkan.glfwCreateWindowSurface(instance, windowHandle, null, pSurface);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan window surface: " + result);
            }
            return pSurface.get(0);
        }
    }

    @Override
    public String[] requiredInstanceExtensions() {
        if (!glfwInitialized) {
            initGlfw();
        }
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

    /**
     * 销毁窗口并释放 GLFW 资源。
     */
    public void dispose() {
        if (windowHandle != NULL) {
            // 释放回调
            if (sizeCallback != null) {
                sizeCallback.free();
                sizeCallback = null;
            }
            glfwDestroyWindow(windowHandle);
            windowHandle = NULL;
            log.info("GLFW window destroyed");
        }
    }

    private static synchronized void initGlfw() {
        if (glfwInitialized) {
            return;
        }

        // 设置错误回调
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // 检查 Vulkan 支持
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new RuntimeException("Vulkan is not supported by GLFW");
        }

        glfwInitialized = true;
        log.info("GLFW initialized (Vulkan supported)");
    }

    /**
     * 终止 GLFW（应在所有窗口销毁后调用）。
     */
    public static synchronized void terminate() {
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
            log.info("GLFW terminated");
        }
    }

    @Override
    public float dpi() {
        return 1;
    }
}
