package com.javelin.core;

import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.GLFWWindow;
import com.javelin.core.window.Window;

public class JavelinEngine {

    private final JavelinSetting setting;

    /** 主窗口：start() 时经 createWindow() 工厂创建，帧循环直接驱动（见 Engine_Window_Design.md） */
    private Window window;

    public JavelinEngine(JavelinSetting setting) {
        this.setting = setting;
    }


    public void start() {
        window = createWindow();
        try {
            window.create();
            window.show();
            mainLoop();
        } finally {
            window.close();
        }
    }

    private void mainLoop(){
        while (!window.closeRequested()){
            window.pollEvents();
        }
    }

    public JavelinSetting setting() {
        return setting;
    }


    /** 窗口工厂：子类可覆盖以替换窗口实现 */
    protected Window createWindow() {
        return new GLFWWindow(setting.getWidth(), setting.getHeight(), setting.getTitle());
    }

    /** 主窗口；start() 调用前为 null */
    public Window window() {
        return window;
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        JavelinEngine javelinEngine = new JavelinEngine(setting);
        javelinEngine.start();
    }

}
