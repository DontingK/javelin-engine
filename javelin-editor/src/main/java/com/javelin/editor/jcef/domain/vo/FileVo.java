package com.javelin.editor.jcef.domain.vo;


import java.io.File;

/**
 * 文件视图。
 *
 * @author: javelin
 * @create: 2026/6/24
 * @Version 1.0
 **/
public class FileVo {
    public String path;
    public String name;
    public boolean directory;

    public FileVo(File file) {
        path = file.getPath();
        name = file.getName();
        directory = file.isDirectory();
    }
}

