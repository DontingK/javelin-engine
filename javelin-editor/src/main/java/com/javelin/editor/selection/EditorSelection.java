package com.javelin.editor.selection;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.scene.Node;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.SceneSelectEvent;

import java.io.File;

/**
 * 编辑器全局选中状态单源（菜单域基类上下文源，设计档 Editor_Menu_Design §3.2）。
 *
 * <p>场景树选中存 Node id（取用经 {@link ObjectCache} 弱引用反查），project 选中存
 * 文件/目录路径。写入方：前端经桥上报（场景树已接线）、创建命令自动选中、将来
 * 视口拾取；setSelectedNodeId 为收口发布点（SceneSelectEvent，见事件包）。</p>
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
     * 当前选中 Node id（场景树拉取回传前端对齐选中态用）。
     *
     * @return 无选中时 null
     */
    public static Integer getSelectedNodeId() {
        return selectedNodeId;
    }

    /**
     * 设置场景树选中（收口发布点）。
     *
     * @param nodeId 选中 Node id，null 清除（前端上报/视口拾取调用）
     */
    public static void setSelectedNodeId(Integer nodeId) {
        selectedNodeId = nodeId;
        // 选中变更事件收口发布（本方法是唯一写入点：桥上报、创建命令自动选中、
        // 将来拾取都过此）；publish 任意线程可调（异步单线程泵分发）
        EditorEventBus.publish(new SceneSelectEvent(nodeId));
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
