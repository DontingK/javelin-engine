package com.javelin.editor;

import com.javelin.editor.window.cef.ProjectWindow;

public class EditorApp {
    static void main(String[] args) {
        ProjectWindow projectWindow = new ProjectWindow();
        projectWindow.setSize(1280,720);
        projectWindow.setVisible(true);
    }
}
