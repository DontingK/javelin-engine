package com.javelin.editor.file;

import java.util.List;

/**
 * 图标型文件类型：纯注册条目图标、无打开操作（{@link #canOpen()} 恒 false）。
 * .java/.json 首批使用。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class IconFileType implements FileType {

    private final String icon;
    private final List<String> extensions;

    public IconFileType(String icon, String... extensions) {
        this.icon = icon;
        this.extensions = List.of(extensions);
    }

    @Override
    public List<String> extensions() {
        return extensions;
    }

    @Override
    public String icon() {
        return icon;
    }
}
