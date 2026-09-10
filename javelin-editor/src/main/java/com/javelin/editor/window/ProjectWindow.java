package com.javelin.editor.window;


public class ProjectWindow extends CefFrame {
    private static ProjectWindow instance;
    public ProjectWindow() {
        loadURL("http://localhost:5173/page/projectManager.html");
        instance=this;
    }

    public static ProjectWindow getInstance() {
        return instance;
    }

}
