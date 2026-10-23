package com.javelin.editor.debug.drawer;

import com.javelin.core.math.Color;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.BoxCollider;
import com.javelin.editor.debug.ComponentDebugLine;
import com.javelin.editor.debug.DebugDrawLine;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * BoxCollider 调试线（Editor_DebugLine_Design §4）：center±size/2 本地 8 角点直乘
 * 节点世界矩阵 → 12 边——OBB 贴合（随物体旋转，Unity collider gizmo 口径）。
 *
 * @author javelin
 * @create 2026/10/23
 */
public class BoxColliderDebugLine implements ComponentDebugLine<BoxCollider> {

    /** 碰撞盒线色（Unity collider 绿；与 mesh bounds 同绿系——同盒类诊断信息） */
    private static final Color COLOR = Color.fromBytes(0, 255, 128, 255);

    /** 8 角点复用数组（热路径零分配） */
    private final Vector3f[] corners = new Vector3f[8];

    public BoxColliderDebugLine() {
        for (int i = 0; i < corners.length; i++) {
            corners[i] = new Vector3f();
        }
    }

    @Override
    public void drawDebugLine(BoxCollider component, Node node) {
        Vector3f center = component.getCenter();
        Vector3f size = component.getSize();
        float hx = size.x * 0.5f;
        float hy = size.y * 0.5f;
        float hz = size.z * 0.5f;

        // 本地角点（bit0/bit1/bit2 = +x/+y/+z 取正）→ 直乘世界矩阵（OBB 贴合）
        Matrix4f localToWorld = node.getTransform().getLocalToWorld();
        for (int i = 0; i < 8; i++) {
            corners[i].set(
                    center.x + ((i & 1) != 0 ? hx : -hx),
                    center.y + ((i & 2) != 0 ? hy : -hy),
                    center.z + ((i & 4) != 0 ? hz : -hz));
            localToWorld.transformPosition(corners[i]);
        }
        // 12 边：bit 翻转法（同盒相邻角点连线）
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) {
                    DebugDrawLine.line(corners[i], corners[i | bit], COLOR);
                }
            }
        }
    }
}
