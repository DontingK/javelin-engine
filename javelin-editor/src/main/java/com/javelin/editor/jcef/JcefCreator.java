package com.javelin.editor.jcef;


import com.javelin.editor.jcef.bridge.JsJavaBridgeHandler;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefMessageRouter;

import java.io.File;

/**
 * @create: 2026/7/7
 * @Version 1.0
 **/
public class JcefCreator {

    private static CefApp cefApp;
    static {
        try {
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void init() throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File("jcef-bundle"));
        builder.setProgressHandler(new ConsoleProgressHandler());
        builder.addJcefArgs("--disable-gpu");

        CefSettings cefSettings = builder.getCefSettings();
        cefSettings.windowless_rendering_enabled = false;

        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
        });
        cefApp = builder.build();
    }

    private static void initClient(CefClient client) {
        // 注册 JS -> Java 桥接消息路由
        CefMessageRouter router = CefMessageRouter.create();
        router.addHandler(new JsJavaBridgeHandler(), true);
        client.addMessageRouter(router);
//        client.addContextMenuHandler(new JcefContextMenuHandler());
    }

    public static CefClient createClient() {
        CefClient newClient = cefApp.createClient();
        initClient(newClient);
        return newClient;
    }

    public static CefBrowser createBrowser(String url) {
        CefClient client = createClient();
        return client.createBrowser(url, false, false);
    }

    public static void close(){
        cefApp.dispose();
    }
}
