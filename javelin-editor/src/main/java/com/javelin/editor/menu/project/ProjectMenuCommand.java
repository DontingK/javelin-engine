package com.javelin.editor.menu.project;

import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.menu.MenuCommand;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.selection.EditorSelection;

import java.io.File;

/**
 * project 面板右键域命令基类（设计档 Editor_Menu_Design §3.2）。域分包：
 * project 域基类与 project 命令同包（menu.project），命令随 project 对接任务落地。
 *
 * <p>提供当前选中文件/目录上下文——来源是 Java 侧全局选中状态单源
 * {@link EditorSelection}（project 选中存路径；写入方为前端经桥上报，
 * 随 project 对接任务接线）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public abstract class ProjectMenuCommand implements MenuCommand {

    /**
     * 当前选中的文件。
     *
     * @return 选中项存在且为文件时返回，否则 null
     */
    protected File getSelectedFile() {
        return EditorSelection.getSelectedFile();
    }

    /**
     * 当前选中的目录。
     *
     * @return 选中项存在且为目录时返回，否则 null
     */
    protected File getSelectedDirectory() {
        return EditorSelection.getSelectedDirectory();
    }

    /** label 默认（editor.core 约定 label key = 命令 id） */
    @Override
    public I18nKey label() {
        return EditorLang.key(id());
    }
}
