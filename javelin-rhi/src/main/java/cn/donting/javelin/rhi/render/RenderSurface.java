package cn.donting.javelin.rhi.render;

/**
 * 呈现目标契约。
 * 由上层实现（GLFW / Swing Canvas），RHI 只消费接口（设计文档第 23 节）。
 */
public interface RenderSurface {

    /** 平台原生窗口句柄：Win32 HWND / X11 Window / macOS NSView 指针（由实现侧翻译，勿直接传 GLFW 句柄） */
    long nativeHandle();

    /** 决定 RHI 加载哪个 surface 扩展 */
    Platform platform();

    /** 呈现区域像素宽度 */
    int width();

    /** 呈现区域像素高度 */
    int height();

    /** 自上次 acknowledgeResize() 后尺寸变化过 */
    boolean isResized();

    /** 确认已处理 resize */
    void acknowledgeResize();

    /** 最小化/隐藏 = false，可跳过渲染；Canvas 恒 true */
    boolean isVisible();
}
