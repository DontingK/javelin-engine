package com.javelin.editor.window;


import com.javelin.core.window.Window;
import com.javelin.editor.jcef.JcefCreator;
import com.javelin.rhi.api.RHIWindow;
import org.cef.browser.CefBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * @create: 2026/7/7
 * @Version 1.0
 **/
public abstract class CefFrame extends JFrame  {
    private static final Logger logger = LoggerFactory.getLogger(CefFrame.class);
    protected CefBrowser cefBrowser;
    protected Component jecfComponent;

    public CefFrame() {
        getContentPane().setLayout(new BorderLayout());
    }

    public void loadURL(String url) {
        cefBrowser = JcefCreator.createBrowser(url);
        jecfComponent = cefBrowser.getUIComponent();
        addJcefComponent(jecfComponent);
    }

    protected void addJcefComponent(Component jecfComponent) {
        getContentPane().add(jecfComponent, BorderLayout.CENTER);
    }


    public CefBrowser getCefBrowser() {
        return cefBrowser;
    }

    public void openDevTools() {
        cefBrowser.openDevTools();
    }

    @Override
    public void dispose() {
        logger.info("cefBrowser close");
        cefBrowser.close(true);
        super.dispose();
    }

    public void send(String type, Object message) {
//        JavaScriptBridge.send(cefBrowser,type, message);
    }

}
