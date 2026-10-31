package cn.donting.javelin.core.window;

import cn.donting.javelin.core.input.InputSink;
import cn.donting.javelin.rhi.render.RenderSurface;

/**
 * 引擎侧窗口抽象（设计文档 doc/Engine_Window_Design.md）。
 *
 * <p>与 {@link RenderSurface} 刻意分离：窗口不一定呈现（Swing Canvas 场景无窗口），
 * 呈现面由 {@link #renderSurface()} 提供、RHI 消费（RHI 设计文档 23.2 控制反转）。</p>
 *
 * <p>本次为<b>最小呈现集</b>：生命周期 + 事件轮询 + 尺寸/resize 感知 + 关闭请求。
 * 键鼠输入经 {@link #attachInputSink} 挂载（ implementations 在原生回调里
 * 翻译成引擎枚举后投递，见 cn.donting.javelin.core.input.InputSink）。</p>
 *
 * <p>GLFW 要求窗口操作与事件轮询在主线程执行，实现侧须保证。</p>
 */
public interface Window {
    /** 创建窗口（隐藏，不显示）；重复调用为 no-op */
    void create();

    /** 显示窗口（未创建时先创建）；重复调用为 no-op */
    void showWindow();

    /** 窗口是否请求关闭（点关闭按钮 / ESC） */
    boolean closeRequested();

    /** 轮询窗口事件（GLFW 要求主线程调用） */
    void pollEvents();

    /**
     * 挂载输入事件出口（传 null 摘除）：实现方在原生键鼠/焦点回调里
     * 翻译成引擎枚举后投递给 sink。default 空实现——不支持原生输入的
     * 窗口实现（如编辑器 Swing 窗口）暂不接入，将来按需实现。
     */
    default void attachInputSink(InputSink sink) {
    }

    /** framebuffer 像素宽度（呈现用；DPI 缩放时与窗口坐标尺寸不同） */
    int width();

    /** framebuffer 像素高度 */
    int height();

    /** 自上次 acknowledgeResize() 后 framebuffer 尺寸变化过（swapchain recreate 触发条件） */
    boolean isResized();

    /** 确认已处理 resize（swapchain recreate 完成后调用） */
    void acknowledgeResize();

    /** 最小化/隐藏 = false，渲染循环可跳过本帧 */
    boolean isVisible();

    /** 释放窗口资源；调用后不可再 show() */
    void close();

    /** 呈现面（RHI 消费，见 {@link RenderSurface}） */
    RenderSurface renderSurface();
}
