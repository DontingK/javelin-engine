package com.javelin.editor.window.cef;


import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class ProjectFrame extends CefFrame {
    private static ProjectFrame instance;
    public ProjectFrame() {
        loadURL("http://localhost:5173/page/projectManager.html");
        instance=this;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        // 添加窗口监听器
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
               dispose();
            }
        });
    }

    public static ProjectFrame getInstance() {
        return instance;
    }

}
