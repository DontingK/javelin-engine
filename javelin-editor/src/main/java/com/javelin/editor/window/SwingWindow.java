package com.javelin.editor.window;

import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.EditorJavelinEngine;
import com.javelin.editor.window.cef.MainWindow;
import org.lwjgl.vulkan.VkInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME;

public class SwingWindow extends Window {

    private static final Logger log = LoggerFactory.getLogger(SwingWindow.class);
    private final JavelinSetting setting;
    private MainWindow mainWindow;
    private EditorJavelinEngine engine;

    public SwingWindow(JavelinSetting setting, EditorJavelinEngine engine) {
        this.setting = setting;
        this.engine = engine;
    }

    @Override
    public void create() {
        mainWindow = new MainWindow(setting);
        mainWindow.create();
        mainWindow.getCanvas().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                engine.submitToExecuteQueue(() -> {
                    onResize(e.getComponent().getWidth(),
                            e.getComponent().getHeight(),dpi());
                });
            }
        });
        mainWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        onResize(setting.getWidth(),
                setting.getHeight(),dpi());
    }

    @Override
    public void show() {
        mainWindow.setVisible(true);
    }

    @Override
    public void hide() {
        mainWindow.setVisible(false);
    }

    @Override
    public void pollEvent() {

    }

    @Override
    public void enginClose() {
        mainWindow.dispose();
    }

    @Override
    public long createSurface(VkInstance instance) {
        return mainWindow.createVulkanSurface(instance);
    }

    @Override
    public String[] requiredInstanceExtensions() {
        return new String[]{
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_WIN32_SURFACE_EXTENSION_NAME
        };
    }

    @Override
    public float dpi() {
        return mainWindow.getDpi();
    }
}
