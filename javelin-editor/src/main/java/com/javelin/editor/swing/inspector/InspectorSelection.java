package com.javelin.editor.swing.inspector;

import com.javelin.core.scene.Node;

import java.io.File;

/**
 * 检查器分发用选中快照（外壳从 {@link com.javelin.editor.selection.EditorSelection}
 * 构造；双槽互斥后两槽至多一非空）。不可变值对象。
 *
 * @author javelin
 * @create 2026/10/21
 */
public final class InspectorSelection {

    private final Node node;
    private final File file;

    /**
     * @param node 选中节点（非节点选中时 null）
     * @param file 选中文件/目录原始路径（非文件选中时 null）
     */
    public InspectorSelection(Node node, File file) {
        this.node = node;
        this.file = file;
    }

    /** 选中节点（null = 非节点选中） */
    public Node node() {
        return node;
    }

    /** 选中文件/目录（null = 非文件选中；目录仅展示侧可达，注册器分发只认文件） */
    public File file() {
        return file;
    }
}
