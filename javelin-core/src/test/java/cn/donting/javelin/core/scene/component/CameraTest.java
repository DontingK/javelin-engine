package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.core.render.ProjectionType;
import cn.donting.javelin.core.math.Rect;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Camera.screenToRay 单测（2026-10-05，Unity ScreenPointToRay 口径——经
 * viewportRect 归一化的屏幕点 → 世界射线）。
 *
 * <p>覆盖：中心沿视向、右/上边缘的半角比例（屏幕右 = forward×up = identity 时
 * 世界 −X——RH 口径判别）、相机世界位姿、viewportRect 子区映射（右半/下半）、
 * 正交与裸组件的显式报错。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class CameraTest {

    /** 解析用例容差 */
    private static final float EPS = 1e-4f;

    /** 60° fov 半角正切 */
    private static final float TAN_HALF = (float) Math.tan(Math.toRadians(60.0) * 0.5);

    private final Ray ray = new Ray();
    private final Vector3f dir = new Vector3f();

    /** 挂节点的相机（Transform 恒随 Node 自动装配） */
    private Camera cameraOnNode() {
        Node node = new Node();
        return node.addComponent(Camera.class);
    }

    @Test
    void centerRayPointsAlongForward() {
        Camera camera = cameraOnNode();
        camera.setFov(60.0f);

        camera.screenToRay(400.0f, 300.0f, 800.0f, 600.0f, ray);

        assertEquals(0.0f, ray.getOrigin().x, EPS);
        assertEquals(0.0f, ray.getOrigin().y, EPS);
        assertEquals(0.0f, ray.getOrigin().z, EPS);
        assertEquals(0.0f, ray.getDirection().x, EPS);
        assertEquals(0.0f, ray.getDirection().y, EPS);
        assertEquals(1.0f, ray.getDirection().z, EPS);
    }

    @Test
    void edgeRaysMatchHalfAngleRatios() {
        Camera camera = cameraOnNode();
        camera.setFov(60.0f);

        // 右边缘：方向 x/z ≈ −tanHalf·aspect（屏幕右 = identity 时世界 −X，RH 口径）
        camera.screenToRay(800.0f, 300.0f, 800.0f, 600.0f, ray);
        dir.set(ray.getDirection());
        assertEquals(-TAN_HALF * (800.0f / 600.0f), dir.x / dir.z, EPS);
        assertEquals(0.0f, dir.y, EPS);

        // 上边缘：方向 y/z ≈ +tanHalf（屏幕顶 = NDC −1 = 视线上方——Vulkan Y 翻转）
        camera.screenToRay(400.0f, 0.0f, 800.0f, 600.0f, ray);
        dir.set(ray.getDirection());
        assertEquals(TAN_HALF, dir.y / dir.z, EPS);
        assertEquals(0.0f, dir.x, EPS);
    }

    @Test
    void rayOffsetsByCameraWorldPose() {
        Camera camera = cameraOnNode();
        camera.setFov(60.0f);
        Node node = camera.getNode();
        node.getTransform().setLocalPosition(new Vector3f(5, 0, 0));
        node.getTransform().setLocalRotation(new Quaternionf().rotationY((float) Math.toRadians(90.0)));

        camera.screenToRay(400.0f, 300.0f, 800.0f, 600.0f, ray);

        // 原点随平移；中心视向 = rotY(90°)·(0,0,1) = (1,0,0)
        assertEquals(5.0f, ray.getOrigin().x, EPS);
        assertEquals(0.0f, ray.getOrigin().y, EPS);
        assertEquals(0.0f, ray.getOrigin().z, EPS);
        assertEquals(1.0f, ray.getDirection().x, EPS);
        assertEquals(0.0f, ray.getDirection().y, EPS);
        assertEquals(0.0f, ray.getDirection().z, EPS);
    }

    @Test
    void viewportRectRightHalfMapsSubRegion() {
        Camera camera = cameraOnNode();
        camera.setFov(60.0f);
        // Unity viewportRect：右半屏（x∈[0.5,1]、y 全高）
        camera.setViewportRect(new Rect(0.5f, 0.0f, 0.5f, 1.0f));

        // 视口中点（screenX=600）→ 中心视向
        camera.screenToRay(600.0f, 300.0f, 800.0f, 600.0f, ray);
        assertEquals(0.0f, ray.getDirection().x, EPS);

        // 视口左缘（screenX=400，画布中缝）→ ndcX=−1：x/z ≈ +tanHalf·视口aspect(400/600)
        camera.screenToRay(400.0f, 300.0f, 800.0f, 600.0f, ray);
        dir.set(ray.getDirection());
        assertEquals(TAN_HALF * (400.0f / 600.0f), dir.x / dir.z, EPS);

        // 视口右缘（screenX=800）→ ndcX=+1：x/z ≈ −tanHalf·视口aspect
        camera.screenToRay(800.0f, 300.0f, 800.0f, 600.0f, ray);
        dir.set(ray.getDirection());
        assertEquals(-TAN_HALF * (400.0f / 600.0f), dir.x / dir.z, EPS);
    }

    @Test
    void viewportRectBottomHalfMapsVerticalSubRegion() {
        Camera camera = cameraOnNode();
        camera.setFov(60.0f);
        // Unity viewportRect：下半屏（y∈[0,0.5]）——鼠标顶边 = my=300（左上原点）
        camera.setViewportRect(new Rect(0.0f, 0.0f, 1.0f, 0.5f));

        // 视口顶（my=300）→ ndcY=−1 → 视线上方
        camera.screenToRay(400.0f, 300.0f, 800.0f, 600.0f, ray);
        dir.set(ray.getDirection());
        assertEquals(TAN_HALF, dir.y / dir.z, EPS);

        // 视口中（my=450）→ 中心视向
        camera.screenToRay(400.0f, 450.0f, 800.0f, 600.0f, ray);
        assertEquals(0.0f, ray.getDirection().y, EPS);

        // 视口底（my=600）→ ndcY=+1 → 视线下方
        camera.screenToRay(400.0f, 600.0f, 800.0f, 600.0f, ray);
        dir.set(ray.getDirection());
        assertEquals(-TAN_HALF, dir.y / dir.z, EPS);
    }

    @Test
    void rejectsOrthoAndBareCamera() {
        Camera ortho = cameraOnNode();
        ortho.setProjectionType(ProjectionType.ORTHOGRAPHIC);
        assertThrows(UnsupportedOperationException.class,
                () -> ortho.screenToRay(400, 300, 800, 600, ray));

        Camera bare = new Camera();
        assertThrows(IllegalStateException.class,
                () -> bare.screenToRay(400, 300, 800, 600, ray));
    }

    @Test
    void directionIsAlwaysNormalized() {
        Camera camera = cameraOnNode();
        camera.setFov(60.0f);

        camera.screenToRay(123.0f, 456.0f, 800.0f, 600.0f, ray);

        assertEquals(1.0f, ray.getDirection().length(), EPS);
        assertTrue(ray.getDirection().z > 0.0f, "identity 相机的任意屏幕点视向 z 分量恒正");
    }
}
