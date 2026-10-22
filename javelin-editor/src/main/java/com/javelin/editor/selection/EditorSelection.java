package com.javelin.editor.selection;

import com.javelin.core.scene.Node;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.ProjectSelectEvent;
import com.javelin.editor.event.SceneSelectEvent;

import java.io.File;

/**
 * 编辑器全局选中状态单源（菜单域基类上下文源，设计档 Editor_Menu_Design §3.2）。
 *
 * <p>场景树选中存 Node 引用（2026-10-18 全链引用化拍板：原「id + ObjectCache
 * 弱引用反查」废弃——弱隔离名存实亡且场景实例会话常驻无泄漏差异，已删/换场景
 * 的僵尸引用由消费方 getScene 归属校验兜底，DeleteMenuCommand 先例），project
 * 选中存文件/目录路径。写入方：层级树点击上报、创建命令自动选中、将来视口拾取；
 * setSelectedNode 为收口发布点（SceneSelectEvent，见事件包）——将来场景切换
 * 命令落地时须置 null 收口。双槽互斥（2026-10-21 拍板，Unity 单 selection
 * 口径）：setSelectedNode 清文件槽（静默）；setSelectedPath 清节点槽并补发
 * SceneSelectEvent(null) 复位节点消费方（Gizmo/层级高亮/变换基线），自身收口
 * 发布 ProjectSelectEvent。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class EditorSelection {

    private static volatile Node selectedNode;
    private static volatile File selectedPath;

    private EditorSelection() {
        // 静态门面禁止实例化
    }

    /**
     * 当前选中 Node（引用直返；已删/换场景的僵尸引用由消费方归属校验兜底）。
     *
     * @return 无选中时 null
     */
    public static Node getSelectedNode() {
        return selectedNode;
    }

    /**
     * 设置场景树选中（收口发布点）。
     *
     * @param node 选中 Node，null 清除（树点击上报/创建命令/视口拾取调用）
     */
    public static void setSelectedNode(Node node) {
        selectedNode = node;
        // 双槽互斥：节点接管选中，文件槽清空（静默——文件侧消费方拉态分发，无平行状态）
        selectedPath = null;
        // 选中变更事件收口发布（本方法是唯一写入点：桥上报、创建命令自动选中、
        // 将来拾取都过此）；publish 任意线程可调（异步单线程泵分发）
        EditorEventBus.publish(new SceneSelectEvent(node));
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
     * 当前选中路径原始值（文件或目录，不做形态过滤——检查器分发等展示侧用）。
     *
     * @return 无选中时 null
     */
    public static File getSelectedPath() {
        return selectedPath;
    }

    /**
     * 设置 project 选中（收口发布点；清空节点槽——双槽互斥，Unity 单 selection 口径）。
     *
     * @param path 选中文件/目录，null 清除（前端上报/层级树根行=场景文件调用）
     */
    public static void setSelectedPath(File path) {
        selectedPath = path;
        Node prevNode = selectedNode;
        selectedNode = null;
        // 节点消费方（Gizmo/层级高亮/变换基线）随选中移交复位
        if (prevNode != null) {
            EditorEventBus.publish(new SceneSelectEvent(null));
        }
        EditorEventBus.publish(new ProjectSelectEvent(path));
    }
}
