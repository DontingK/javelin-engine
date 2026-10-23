package com.javelin.editor.debug.drawer;

import com.javelin.core.math.Color;
import com.javelin.core.physics.collision.Aabb;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.editor.debug.ComponentDebugLine;
import com.javelin.editor.debug.DebugDrawLine;

/**
 * MeshRenderer 调试线（Editor_DebugLine_Design §4）：mesh 本地 bounds 变换世界
 * AABB 12 边——世界轴对齐（旋转呼吸，Unity 口径；与将来视锥剔除判定盒同形态）。
 *
 * @author javelin
 * @create 2026/10/23
 */
public class MeshDebugLine implements ComponentDebugLine<MeshRenderer> {

    /** bounds 线色（Unity collider/bounds 观感绿） */
    private static final Color COLOR = Color.fromBytes(0, 220, 0, 255);

    /** 世界盒复用（每帧 transform 回写，热路径零分配） */
    private final Aabb worldBounds = new Aabb();

    @Override
    public void drawDebugLine(MeshRenderer component, Node node) {
        if (component.getMesh() == null) {
            return;
        }
        // 世界 AABB：中心变换 + 列绝对值展开（含旋转呼吸），Aabb#transform 现成语义
        component.getMesh().getBounds().transform(node.getTransform().getLocalToWorld(), worldBounds);
        DebugDrawLine.aabb(worldBounds, COLOR);
    }
}
