package com.javelin.editor.appstate.gizmo;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.physics.Ray;
import com.javelin.core.scene.Node;
import org.joml.Vector3f;

/**
 * 变换工具基类（移动/缩放/旋转工具族，契约 2026-10-04，驱动面 2026-10-05）。
 *
 * <p>工具自持 Gizmo 子树（{@link #toolNode}，init 构建填充后挂到驱动侧
 * gizmoNode 下）；{@link TransformGizmoAppState} 为驱动面——选中事件、跟随、
 * 显隐、距离自适应缩放都在驱动侧，工具族零感知。工具切换位 =
 * {@link #show()}/{@link #hide()}（节点激活态，渲染过滤按
 * isActiveInHierarchy——引擎 SceneRenderBridge 统一口径）。</p>
 *
 * <p><b>拖拽契约（2026-10-06）</b>：驱动侧管输入状态机（LMB 边沿/持续态、
 * hover 检测与锁定、目标有效性），工具实现三段式拖拽行为——
 * {@link #beginDrag}（按下命中本工具轴节点）→ {@link #updateDrag}（每帧
 * apply）→ {@link #endDrag}（释放/强制收尾）。工具自持拖拽几何状态，
 * 按工具各自语义应用（移动=沿轴平移，旋转/缩放将来各自实现）。</p>
 *
 * <p><b>可选性与视觉状态（2026-10-06）</b>：{@link #isSelectable}——节点在当前
 * 视线（相机→选中目标，与鼠标位置无关）下是否可进入 hover/拖拽（移动轴与
 * 视线近平行时端视退化不可选；旋转柄几何相反，故归工具裁决，默认 true）；
 * {@link #updateVisuals}——驱动侧每帧同步视觉状态（hover 与拖拽共用，拖拽中
 * hover 锁定即被拖轴；含不可选变灰，判定与 isSelectable 同源；工具内部幂等，
 * 默认空实现）。</p>
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

    /**
     * 开始拖拽（驱动侧判定 LMB 按下且 hover 命中本工具轴节点后调用一次；工具自持拖拽状态）。
     *
     * @param axisNode 命中的轴节点（userData 带 {@link Axis} 标；射线检测返回所在节点）
     * @param target 拖拽目标（当前选中节点）
     * @param pickRay 按下帧鼠标世界射线（{@code Camera.screenToRay} 产物，方向已归一化）
     * @param camWorldPos 相机世界位置（拖拽平面构造用——工具不持引擎引用，驱动侧供上下文）
     * @return true = 拖拽成立（驱动侧进入拖拽态）；false = 无法开始（驱动侧保持非拖拽）
     */
    protected abstract boolean beginDrag(Node axisNode, Node target, Ray pickRay, Vector3f camWorldPos);

    /**
     * 拖拽中每帧（LMB 持续按住；pickRay = 当前鼠标世界射线）。目标失效/选中变更
     * 由驱动侧检测并强制 {@link #endDrag}，本方法只管 apply。
     */
    protected abstract void updateDrag(Node target, Ray pickRay);

    /**
     * 结束拖拽（LMB 释放或驱动侧强制——目标失效/选中变更/摘除状态；
     * 恢复轴高亮等收尾在此做，拖拽几何状态由下次 beginDrag 全量重建）。
     */
    protected abstract void endDrag();

    /**
     * 节点在当前视线下是否可选中（hover/拖拽入口的唯一闸门，驱动侧射线检测
     * 过滤）。视线 = 相机 → 选中目标方向（归一化，与鼠标位置无关——用户拍板
     * 2026-10-06）。按工具几何语义裁决：如移动轴与视线趋于平行（端视退化、
     * 拖拽投影病态）不可选；旋转柄几何相反（正对最好抓）将来各自实现。默认 true。
     */
    protected boolean isSelectable(Node node, Vector3f viewDir) {
        return true;
    }

    /**
     * 视觉状态同步（驱动侧每帧调用；highlightedNode = 本工具子树内 hover 命中/
     * 拖拽锁定的节点或 null——非本工具节点由驱动侧滤为 null；viewDir = 相机 →
     * 选中目标方向，与鼠标位置无关，勿改写）。工具按自身几何计算各柄视觉
     * （正常/高亮/不可选变灰等）并幂等写色——不可选判定应与 {@link #isSelectable}
     * 同源，保证视觉与可选性一致。默认空实现（无视觉状态的工具免实现）。
     */
    protected void updateVisuals(Node highlightedNode, Vector3f viewDir) {
    }

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
