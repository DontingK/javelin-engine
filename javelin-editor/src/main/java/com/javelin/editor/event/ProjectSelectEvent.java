package com.javelin.editor.event;

import java.io.File;

/**
 * 项目选中变更事件（project.select）：{@link com.javelin.editor.selection.EditorSelection}
 * 在 setSelectedPath 收口发布——项目面板左树/右区/面包屑选中、右键先落选中都过该
 * setter，一处发布全覆盖。消费方：检查器外壳分发、层级面板场景根行跨域对齐。
 * null = 清除选中；发布方可在
 * 任意线程，Swing 消费方自行 EDT hop（Editor_Swing_Design §7.3）。
 *
 * @author javelin
 * @create 2026/10/21
 */
public final class ProjectSelectEvent implements EditorEvent {

    /** 选中路径（null = 清除选中） */
    private final File path;

    /**
     * @param path 选中文件/目录，null = 清除选中
     */
    public ProjectSelectEvent(File path) {
        this.path = path;
    }

    /** 选中路径（null = 清除选中） */
    public File getPath() {
        return path;
    }
}
