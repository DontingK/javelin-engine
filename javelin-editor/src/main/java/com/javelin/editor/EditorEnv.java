package com.javelin.editor;



import com.javelin.editor.util.FileUtil;

import java.io.File;

/**
 * @author: javelin
 * @create: 2026/4/13
 * @Version 1.0
 **/
public class EditorEnv {

    public static boolean isRelease() {
        return EditorEnv.class.getProtectionDomain().getCodeSource().getLocation().getPath().contains(".jar");
    }
    public static boolean isDev() {
        return !isRelease();
    }


    public static File getEditorRuntimeDir() {
        return new File(System.getProperty("user.dir"));
    }

    public static File getShaderIncludeDir() {
        return FileUtil.createFileByPath(getEditorRuntimeDir(), "shader", "include");
    }

    public static void main(String[] args) {
        System.out.println(getShaderIncludeDir().getAbsolutePath());
    }
}
