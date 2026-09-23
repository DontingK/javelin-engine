package com.javelin.core;

import com.javelin.core.assets.AssetLibManager;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.subsystem.Subsystem;
import com.javelin.core.subsystem.SubsystemManager;
import com.javelin.core.window.GLFWWindow;
import com.javelin.core.window.Window;

import java.io.File;

public class JavelinEngine {

    private final JavelinSetting setting;

    /**
     * 主窗口：start() 时经 createWindow() 工厂创建，帧循环直接驱动（见 Engine_Window_Design.md）
     */
    private Window window;
    private AssetManager assetManager;
    private SubsystemManager subsystemManager;

    private long lastFrameTime;

    public JavelinEngine(JavelinSetting setting) {
        this.setting = setting;
    }


    public void start() {

        try {
            initialize();
            onInitialize();
            mainLoop();
        } finally {
            window.close();
        }
    }

    private final void initialize() {
        window = createWindow();
        window.create();
        window.show();

        assetManager = createAssetManager();
        subsystemManager = createSubsystemManager();
    }

    protected void onInitialize() {

    }

    private void mainLoop() {
        Time.init();
        lastFrameTime = System.nanoTime();
        while (!window.closeRequested()) {
            window.pollEvents();
            // 3. 计算 deltaTime
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = currentTime;
            Time.update(deltaTime);
            update();
        }
    }

    public JavelinSetting setting() {
        return setting;
    }


    /**
     * 窗口工厂：子类可覆盖以替换窗口实现
     */
    protected Window createWindow() {
        return new GLFWWindow(setting.getWidth(), setting.getHeight(), setting.getTitle());
    }

    protected AssetManager createAssetManager() {
        return new AssetLibManager(new File("./Library"));
    }

    protected SubsystemManager createSubsystemManager() {
        return new SubsystemManager();
    }

    /**
     * 主窗口；start() 调用前为 null
     */
    public Window window() {
        return window;
    }

    protected void update() {

    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        JavelinEngine javelinEngine = new JavelinEngine(setting);
        javelinEngine.start();
    }

}
