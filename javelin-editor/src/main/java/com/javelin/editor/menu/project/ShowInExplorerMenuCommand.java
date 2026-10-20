package com.javelin.editor.menu.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;

/**
 * 「在资源管理器中显示」命令（project.showInExplorer，Unity Show in Explorer 口径）。
 *
 * <p>文件 → 系统文件管理器定位到所在文件夹并选中该文件
 * （{@link Desktop#browseFileDirectory(File)}，JDK9+）；目录 → 直接打开该文件夹
 * （{@link Desktop#open(File)}）。平台不支持 BROWSE_FILE_DIR 或文件不存在时记
 * warn 降级（不阻断编辑器）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class ShowInExplorerMenuCommand extends ProjectMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShowInExplorerMenuCommand.class);

    @Override
    public String id() {
        return "project.showInExplorer";
    }

    /** 有任一选中（文件或目录）才可用 */
    @Override
    public Boolean enabled() {
        return getSelectedFile() != null || getSelectedDirectory() != null;
    }

    @Override
    public void execute() {
        File file = getSelectedFile();
        File dir = getSelectedDirectory();
        if (file == null && dir == null) {
            return;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (dir != null) {
                desktop.open(dir);
            } else {
                desktop.browseFileDirectory(file);
            }
        } catch (Exception e) {
            LOGGER.warn("在资源管理器中显示失败: {}", file != null ? file : dir, e);
        }
    }
}
