package cn.donting.javelin.editor.menu.project;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;

/**
 * 「复制路径」命令（project.copyPath，Unity Copy Path 口径）：选中项绝对路径写
 * 系统剪贴板。
 *
 * @author javelin
 * @create 2026/10/17
 */
public class CopyPathMenuCommand extends ProjectMenuCommand {

    @Override
    public String id() {
        return "project.copyPath";
    }

    /** 有任一选中（文件或目录）才可用 */
    @Override
    public Boolean enabled() {
        return getSelectedFile() != null || getSelectedDirectory() != null;
    }

    @Override
    public void execute() {
        File file = getSelectedFile();
        File path = file != null ? file : getSelectedDirectory();
        if (path == null) {
            return;
        }
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(path.getAbsolutePath()), null);
    }
}
