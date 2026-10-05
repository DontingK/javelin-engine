package com.javelin.editor.appstate.gizmo;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.scene.Node;

/**
 * 变换工具基类（移动/缩放/旋转工具族，契约 2026-10-04，驱动面 2026-10-05）。
 *
 * <p>工具自持 Gizmo 子树（{@link #toolNode}，init 构建填充后挂到驱动侧
 * gizmoNode 下）；{@link TransformGizmoAppState} 为驱动面——选中事件、跟随、
 * 显隐、距离自适应缩放都在驱动侧，工具族零感知。工具切换位 =
 * {@link #show()}/{@link #hide()}（节点激活态，渲染过滤按
 * isActiveInHierarchy——引擎 SceneRenderBridge 统一口径）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public abstract class TransformTools {

    /** 工具 Gizmo 子树根（init 成功构建填充；资产加载失败留 null——驱动侧安全跳过） */
    protected Node toolNode;

    /**
     * 初始化资源
     */
    protected abstract void init(AssetManager assetManager, JavelinEngine javelinEngine);

    /** 每帧驱动（对齐/自更新语义；选中解析与缩放在驱动侧） */
    protected abstract void update();

    /** 工具名（日志/将来工具切换 UI 用） */
    protected abstract String getName();

    /** 工具 Gizmo 子树根（init 失败为 null，驱动侧据此安全跳过） */
    public Node getToolNode() {
        return toolNode;
    }

    /** 显示本工具（节点激活态翻转；渲染过滤按 isActiveInHierarchy；init 失败空守卫） */
    protected void show() {
        if (toolNode != null) {
            toolNode.setActive(true);
        }
    }

    /** 隐藏本工具（工具切换时旧工具调用；渲染过滤同上；init 失败空守卫） */
    protected void hide() {
        if (toolNode != null) {
            toolNode.setActive(false);
        }
    }

    /**
     * 单位系数的缩放。 缩放到单位长度。
     * TransformGizmoAppState 统一再次缩放
     * @return
     */
    protected float scaleFactor() {
        return 1f;
    }
}
