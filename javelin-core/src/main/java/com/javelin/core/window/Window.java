package com.javelin.core.window;

import com.javelin.rhi.api.RHIWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 窗口抽象基类，提供跨平台窗口的通用属性和生命周期管理。
 * <p>
 * 具体实现（GLFW、Swing 等）继承此类，实现平台相关的 Surface 创建和事件轮询。
 */
public abstract class Window implements RHIWindow {

    private static final Logger log = LoggerFactory.getLogger(Window.class);
    protected int width;
    protected int height;
    protected String title;
    protected boolean resizable;
    protected boolean shouldClose;

    protected final List<ResizeCallback> resizeCallbacks = new ArrayList<>();

    /**
     * 创建窗口（平台相关实现）。
     */
    public abstract void create();

    /**
     * 显示窗口。
     */
    public abstract void show();

    /**
     * 隐藏窗口。
     */
    public abstract void hide();

    /**
     * 轮询窗口事件（非阻塞）。
     */
    public abstract void pollEvent();

    /**
     * 检查窗口是否应该关闭。
     */
    public boolean shouldClose() {
        return shouldClose;
    }

    /**
     * 请求关闭窗口。
     */
    public void requestClose() {
        this.shouldClose = true;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isResizable() {
        return resizable;
    }

    /**
     * 添加窗口尺寸改变监听器。
     */
    public void addResizeCallback(ResizeCallback callback) {
        this.resizeCallbacks.add(callback);
    }

    /**
     * 移除窗口尺寸改变监听器。
     */
    public void removeResizeCallback(ResizeCallback callback) {
        this.resizeCallbacks.remove(callback);
    }

    protected void onResize(int newWidth, int newHeight,float dpi) {
        log.info("onResize:{}x{} dpi:{}",newWidth,newHeight,dpi);
        this.width = (int)(newWidth*dpi);
        this.height = (int)(newHeight*dpi);
        for (ResizeCallback callback : resizeCallbacks) {
            callback.onResize(newWidth, newHeight);
        }
    }

    public void enginClose() {
    }

    @FunctionalInterface
    public interface ResizeCallback {
        void onResize(int width, int height);
    }


}
