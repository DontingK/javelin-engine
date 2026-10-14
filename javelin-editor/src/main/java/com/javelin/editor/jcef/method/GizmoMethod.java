package com.javelin.editor.jcef.method;

import com.javelin.editor.appstate.gizmo.TransformGizmoAppState;
import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.GizmoSpacePar;
import com.javelin.editor.jcef.domain.par.GizmoToolPar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gizmo 桥方法：/gizmo/setTool（变换工具切换）、/gizmo/setSpace（坐标空间切换）——
 * 场景视图工具栏（Tools 组 + 局部/世界）的消费口。
 *
 * <p>桥线程只写 {@link TransformGizmoAppState} 的 <b>static volatile 意图镜像</b>，
 * 引擎线程 update 帧内消费（跨线程镜像先例=GridMenuCommand：AppStateManager
 * 非并发勿跨线程 getState；本类经 ClassUtil 扫描反射实例化、无构造注入，
 * AppState 实例引用不可达，故镜像走 static）。未知值 warn+忽略（对齐
 * /scene/setModel 口径；未注册工具名由 AppState 侧查名落空维持现状）。</p>
 *
 * @author javelin
 * @create 2026/10/14
 **/
@BridgeMethod("/gizmo")
public class GizmoMethod {

    private static final Logger LOGGER = LoggerFactory.getLogger(GizmoMethod.class);

    /**
     * 变换工具切换（场景视图工具栏 Tools 组）。当前仅 "move"（移动）——
     * "rotate"/"scale" 工具未落地，前端按钮置灰占位。
     */
    @BridgeMethod("/setTool")
    public void setTool(GizmoToolPar par) {
        String tool = par == null ? null : par.tool;
        if (tool == null || tool.isBlank()) {
            return;
        }
        TransformGizmoAppState.setDesiredToolName(tool.trim());
    }

    /**
     * 坐标空间切换（局部/世界）。仅影响 Gizmo 轴朝向与拖拽轴——Inspector 恒
     * local 显示（Unity Global/Local 口径）；默认世界（现状语义）。
     */
    @BridgeMethod("/setSpace")
    public void setSpace(GizmoSpacePar par) {
        String space = par == null ? null : par.space;
        if (space == null || space.isBlank()) {
            return;
        }
        String value = space.trim();
        if ("world".equals(value)) {
            TransformGizmoAppState.setLocalSpace(false);
        } else if ("local".equals(value)) {
            TransformGizmoAppState.setLocalSpace(true);
        } else {
            LOGGER.warn("忽略未知坐标空间: {}", space);
        }
    }
}
