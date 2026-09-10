package com.javelin.editor.jcef.method;

import com.javelin.editor.jcef.bridge.BridgeMethod;
import org.lwjgl.util.tinyfd.TinyFileDialogs;


@BridgeMethod("/filesystem")
public class FileSystemMethod {

    /**
     *
     * 选择目录
     *
     * @return
     */
    @BridgeMethod("/select/dir")
    public String selectDir() {
        // 第一个参数：对话框标题；第二个参数：初始打开的目录，传null用系统默认
        String selectedDir = TinyFileDialogs.tinyfd_selectFolderDialog("选择目标文件夹", null);
        return selectedDir;
    }
}
