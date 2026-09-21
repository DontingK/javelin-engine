package com.javelin.core.window;

import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW 实现的 {@link Window}（桌面端）。
 * 对照设计文档 doc/Engine_Window_Design.md。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>GLFW 全局 init/terminate 引用计数——首个窗口 init、最后一个关闭 terminate</li>
 *   <li>create() 隐藏创建（GLFW_VISIBLE=false），show() 显示，避免白屏闪烁</li>
 *   <li>GLFW_CLIENT_API=NO_API：Vulkan 呈现，不创建 OpenGL 上下文</li>
 *   <li>ESC 释放关闭窗口（窗口生命周期便利，非输入系统范畴）</li>
 *   <li>resize 采用每帧轮询 framebuffer 尺寸而非回调（实现简单，与 RenderSurface 查询模型一致）</li>
 * </ul>
 *
 * <p>所有 GLFW 调用必须在主线程执行。</p>
 */
public final class GLFWWindow implements Window {

    private static final Logger log = LoggerFactory.getLogger(GLFWWindow.class);

    /** glfwInit / glfwTerminate 引用计数 */
    private static int glfwRefCount = 0;

    private final int requestedWidth;
    private final int requestedHeight;
    private final String title;

    private long handle = NULL;
    private GLFWKeyCallback keyCallback;   // 持有引用防止 GC（native 侧仅持指针）
    private GLFWRenderSurface renderSurface;
    private boolean created = false;
    private boolean shown = false;
    private boolean closed = false;

    public GLFWWindow(int width, int height, String title) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Window size must be positive: " + width + "x" + height);
        }
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("Window title must not be null or empty");
        }
        this.requestedWidth = width;
        this.requestedHeight = height;
        this.title = title;
    }



    @Override
    public synchronized void create() {
        if (created) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Window has been closed and cannot be created again");
        }
        glfwAddRef();
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);   // Vulkan 呈现，不创建 OpenGL 上下文
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);       // 隐藏创建，由 show() 显示
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

            handle = glfwCreateWindow(requestedWidth, requestedHeight, title, NULL, NULL);
            if (handle == NULL) {
                throw new IllegalStateException("glfwCreateWindow failed");
            }

            keyCallback = GLFWKeyCallback.create((window, key, scancode, action, mods) -> {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, true);
                }
            });
            glfwSetKeyCallback(handle, keyCallback);

            renderSurface = new GLFWRenderSurface(this);
            created = true;
            log.info("GLFW window created: {}x{}, title='{}'", requestedWidth, requestedHeight, title);
        } catch (RuntimeException e) {
            if (handle != NULL) {
                glfwDestroyWindow(handle);
                handle = NULL;
            }
            glfwReleaseRef();
            throw e;
        }
    }

    @Override
    public synchronized void show() {
        if (shown) {
            return;
        }
        glfwShowWindow(handle);
        shown = true;
    }

    @Override
    public boolean closeRequested() {
        return created && glfwWindowShouldClose(handle);
    }

    @Override
    public void pollEvents() {
        if (created) {
            glfwPollEvents();
        }
    }

    @Override
    public int width() {
        return created ? framebufferSize()[0] : requestedWidth;
    }

    @Override
    public int height() {
        return created ? framebufferSize()[1] : requestedHeight;
    }

    @Override
    public boolean isResized() {
        return renderSurface != null && renderSurface.isResized();
    }

    @Override
    public void acknowledgeResize() {
        if (renderSurface != null) {
            renderSurface.acknowledgeResize();
        }
    }

    @Override
    public boolean isVisible() {
        return shown && glfwGetWindowAttrib(handle, GLFW_ICONIFIED) == GLFW_FALSE;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        created = false;
        shown = false;
        renderSurface = null;
        keyCallback = null;   // glfwDestroyWindow 会释放 native 侧回调
        if (handle != NULL) {
            glfwDestroyWindow(handle);
            handle = NULL;
        }
        glfwReleaseRef();
        log.info("GLFW window closed");
    }

    @Override
    public RenderSurface renderSurface() {
        if (renderSurface == null) {
            throw new IllegalStateException("Window has not been created yet");
        }
        return renderSurface;
    }

    /** GLFW 窗口句柄（包私有，供 GLFWRenderSurface 桥接） */
    long nativeHandle() {
        return handle;
    }

    /** 查询当前 framebuffer 像素尺寸（每帧轮询，DPI 缩放安全） */
    int[] framebufferSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            return new int[]{w.get(0), h.get(0)};
        }
    }

    private static synchronized void glfwAddRef() {
        if (glfwRefCount == 0) {
            if (glfwSetErrorCallback(null) == null) {
                GLFWErrorCallback.createPrint(System.err).set();
            }
            if (!glfwInit()) {
                throw new IllegalStateException("glfwInit failed");
            }
        }
        glfwRefCount++;
    }

    private static synchronized void glfwReleaseRef() {
        if (glfwRefCount > 0 && --glfwRefCount == 0) {
            glfwTerminate();
        }
    }
}
