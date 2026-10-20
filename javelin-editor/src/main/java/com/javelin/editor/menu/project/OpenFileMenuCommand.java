package com.javelin.editor.menu.project;

import com.javelin.editor.file.FileType;
import com.javelin.editor.file.FileTypeRegistry;

import java.io.File;

/**
 * 打开选中文件（project.open，Unity Open 口径）：按 {@link FileTypeRegistry}
 * 分发——首批仅 .scene（{@code SceneFileType} 场景切换，脏场景三键确认）；
 * 不可打开类型（未注册/IconFileType）置灰。与项目面板双击同一条分发链。
 *
 * @author javelin
 * @create 2026/10/18
 */
public class OpenFileMenuCommand extends ProjectMenuCommand {

    @Override
    public String id() {
        return "project.open";
    }

    @Override
    public String shortcut() {
        return "Enter";
    }

    @Override
    public Boolean enabled() {
        File file = getSelectedFile();
        FileType type = FileTypeRegistry.of(file);
        return type != null && type.canOpen();
    }

    @Override
    public void execute() {
        File file = getSelectedFile();
        if (file == null) {
            return;
        }
        FileType type = FileTypeRegistry.of(file);
        if (type != null && type.canOpen()) {
            type.open(file);
        }
    }
}
