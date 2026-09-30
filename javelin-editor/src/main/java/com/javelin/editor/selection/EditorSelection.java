package com.javelin.editor.selection;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.scene.Node;

import java.io.File;

/**
 * 编辑器全局选中状态单源（菜单域基类上下文源，设计档 Editor_Menu_Design §3.2）。
 *
 * <p>场景树选中存 Node id（取用经 {@link ObjectCache} 弱引用反查），project 选中存
 * 文件/目录路径。写入方：前端经桥上报（场景树/project 对接任务接线）、将来视口拾取。
 * 当前无写入方——读取恒为无选中（null），域基类与命令先可编译落地。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class EditorSelection {

    private static volatile Integer selectedNodeId;
    private static volatile File selectedPath;

    private EditorSelection() {
        // 静态门面禁止实例化
    }

    /**
     * 当前选中 Node。
     *
     * @return 无选中或对象已被回收时 null
     */
    public static Node getSelectedNode() {
        Integer id = selectedNodeId;
        return id == null ? null : ObjectCache.get(id);
    }

    /**
     * 设置场景树选中。
     *
     * @param nodeId 选中 Node id，null 清除（前端上报/视口拾取调用）
     */
    public static void setSelectedNodeId(Integer nodeId) {
        selectedNodeId = nodeId;
    }

    /**
     * 当前选中的文件。
     *
     * @return 选中项存在且为文件时返回，否则 null
     */
    public static File getSelectedFile() {
        File path = selectedPath;
        return path != null && path.isFile() ? path : null;
    }

    /**
     * 当前选中的目录。
     *
     * @return 选中项存在且为目录时返回，否则 null
     */
    public static File getSelectedDirectory() {
        File path = selectedPath;
        return path != null && path.isDirectory() ? path : null;
    }

    /**
     * 设置 project 选中。
     *
     * @param path 选中文件/目录，null 清除（前端上报调用）
     */
    public static void setSelectedPath(File path) {
        selectedPath = path;
    }
}
