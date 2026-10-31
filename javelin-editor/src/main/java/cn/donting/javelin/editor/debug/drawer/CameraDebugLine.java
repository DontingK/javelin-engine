package cn.donting.javelin.editor.debug.drawer;

import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.math.Rect;
import cn.donting.javelin.core.render.ProjectionType;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.debug.ComponentDebugLine;
import cn.donting.javelin.editor.debug.DebugDrawLine;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Camera 调试线（Editor_DebugLine_Design §4）：视锥线框 12 边（近/远矩形各 4 边 +
 * 4 侧边）。本地角点由 fov/near/far（或 orthoSize）× aspect 算出、直乘相机节点
 * 世界矩阵——OBB 式，从任何角度观察形状正确；相机 forward=+Z（screenToRay 同源）。
 *
 * <p>aspect = viewportRect 宽高比 × 场景视口宽高比（全屏 rect 即视口宽高比；
 * 编辑器依赖注入是 drawer 层唯一例外——相机视锥的 aspect 语义上就是「渲染到哪个面」）。
 * 选中门控天然排除编辑器相机自身（编辑子场景节点不可选中）。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
public class CameraDebugLine implements ComponentDebugLine<Camera> {

    /** 视锥线色（Unity 相机 gizmo 黄） */
    private static final Color COLOR = Color.fromBytes(255, 220, 0, 255);

    private final JavelinEditor editor;

    /** 8 角点复用数组（本地算好变换世界，热路径零分配） */
    private final Vector3f[] corners = new Vector3f[8];

    public CameraDebugLine(JavelinEditor editor) {
        this.editor = editor;
        for (int i = 0; i < corners.length; i++) {
            corners[i] = new Vector3f();
        }
    }

    @Override
    public void drawDebugLine(Camera camera, Node node) {
        float aspect = aspectFor(camera);
        float nearH;
        float farH;
        if (camera.getProjectionType() == ProjectionType.PERSPECTIVE) {
            // fov 为垂直视场角（度）；近/远半高 = tan(fov/2) × 距离
            float tanHalfFov = (float) Math.tan(Math.toRadians(camera.getFov()) * 0.5f);
            nearH = tanHalfFov * camera.getNear();
            farH = tanHalfFov * camera.getFar();
        } else {
            // orthographicSize = 半高（Unity 口径），近远同宽高
            nearH = camera.getOrthographicSize();
            farH = nearH;
        }
        float nearW = nearH * aspect;
        float farW = farH * aspect;
        float near = camera.getNear();
        float far = camera.getFar();

        // 本地角点（bit0/bit1/bit2 = +x/+y 取最大；z = near/far）→ 直乘世界矩阵（OBB）
        Matrix4f localToWorld = node.getTransform().getLocalToWorld();
        for (int i = 0; i < 8; i++) {
            boolean zFar = (i & 4) != 0;
            float w = zFar ? farW : nearW;
            float h = zFar ? farH : nearH;
            corners[i].set(
                    (i & 1) != 0 ? w : -w,
                    (i & 2) != 0 ? h : -h,
                    zFar ? far : near);
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

    /** 视口宽高比：viewportRect 比例 × 场景视口（画布未就绪时退 1） */
    private float aspectFor(Camera camera) {
        int vpW = editor.viewportWidth();
        int vpH = editor.viewportHeight();
        if (vpW <= 0 || vpH <= 0) {
            return 1.0f;
        }
        Rect rect = camera.getViewportRect();
        float rectW = rect != null ? rect.width : 1.0f;
        float rectH = rect != null ? rect.height : 1.0f;
        return (rectW * vpW) / (rectH * vpH);
    }
}
