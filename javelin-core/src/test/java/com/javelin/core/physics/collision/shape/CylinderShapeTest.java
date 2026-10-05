package com.javelin.core.physics.collision.shape;

import com.javelin.core.physics.Ray;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圆柱形状相交单测（片④提前实现，Engine_Collision_Design.md §7）。
 *
 * <p>解析解对照：侧面/顶盖/底盖命中（法线各形态）、横向未命中（out 参不变）、
 * 侧面根越 y 界未命中、切线掠过、斜射入侧面（端盖根更远被最小值淘汰）、
 * origin 内部出射（侧面/端盖两路）、身后未命中、非归一化方向 t 以实长为单位、
 * 零方向退化；外加 §4.2 缩放吸收不变量直证（T·R·S 逆变换射线）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class CylinderShapeTest {

    /** 解析用例浮点容差 */
    private static final float EPS = 1e-4f;

    /** 矩阵链路用例容差（invert/transform 多级舍入） */
    private static final float MATRIX_EPS = 1e-3f;

    // 未命中不变哨兵：验证 out 参复用契约
    private final Vector3f hitPoint = new Vector3f(9, 9, 9);
    private final Vector3f hitNormal = new Vector3f(8, 8, 8);

    @Test
    void rayHitsLateralSurfaceFromFront() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void rayHitsTopCapFromAbove() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        Ray ray = new Ray(new Vector3f(0, 5, 0), new Vector3f(0, -1, 0));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        // 轴向射线无侧面候选；顶盖 t=4，底盖 t=6——最小边界穿越取顶盖
        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(1.0f, hitPoint.y, EPS);
        assertEquals(0.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(1.0f, hitNormal.y, EPS);
        assertEquals(0.0f, hitNormal.z, EPS);
    }

    @Test
    void rayHitsBottomCapFromBelow() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        Ray ray = new Ray(new Vector3f(0, -5, 0), new Vector3f(0, 1, 0));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(-1.0f, hitPoint.y, EPS);
        assertEquals(0.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(-1.0f, hitNormal.y, EPS);
        assertEquals(0.0f, hitNormal.z, EPS);
    }

    @Test
    void rayMissesLaterallyLeavesOutParamsUnchanged() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // 与轴距离 2 > 半径 1：判别式为负无侧面根，轴向分量 0 无端盖候选
        Ray ray = new Ray(new Vector3f(2, 0, -5), new Vector3f(0, 0, 1));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(-1.0f, t, EPS);
        assertEquals(9.0f, hitPoint.x, EPS);
        assertEquals(9.0f, hitPoint.y, EPS);
        assertEquals(9.0f, hitPoint.z, EPS);
        assertEquals(8.0f, hitNormal.x, EPS);
        assertEquals(8.0f, hitNormal.y, EPS);
        assertEquals(8.0f, hitNormal.z, EPS);
    }

    @Test
    void rayMissesWhenLateralRootsOutsideHeightRange() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // 侧面根存在（t=4/6）但 y=2 超出 [−1,1]，且 dy=0 无端盖候选
        Ray ray = new Ray(new Vector3f(0, 2, -5), new Vector3f(0, 0, 1));

        assertEquals(-1.0f, cylinder.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void rayTangentToLateralSurfaceStillCountsAsHit() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // 判别式恰为零（整数运算精确）：相切于 (1,0,0)
        Ray ray = new Ray(new Vector3f(1, 0, -5), new Vector3f(0, 0, 1));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(5.0f, t, EPS);
        assertEquals(1.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(0.0f, hitPoint.z, EPS);
        assertEquals(1.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(0.0f, hitNormal.z, EPS);
    }

    @Test
    void diagonalRayEntersThroughLateralNotCap() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // 斜向上射线：侧面入射根 2√2 ≈ 2.828，顶盖根 3√2 ≈ 4.243——最小值取侧面
        Vector3f dir = new Vector3f(0, 1, 1).normalize();
        Ray ray = new Ray(new Vector3f(0, -2, -3), dir);

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(2.0f * (float) Math.sqrt(2.0), t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void rayFromInsideHitsExitLateral() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 1));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        // 契约：origin 在内部 → 出射点命中（近侧侧面根为负被淘汰）
        assertEquals(1.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(1.0f, hitNormal.z, EPS);
    }

    @Test
    void rayFromInsideHitsExitCap() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // 轴向内部射线：顶盖根 t=1、底盖根 t=-1（负淘汰）
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 1, 0));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(1.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(1.0f, hitPoint.y, EPS);
        assertEquals(0.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(1.0f, hitNormal.y, EPS);
        assertEquals(0.0f, hitNormal.z, EPS);
    }

    @Test
    void cylinderBehindRayOriginMisses() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // 所在直线有侧面根但两根皆负：半直线语义 = 未命中
        Ray ray = new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, 1));

        assertEquals(-1.0f, cylinder.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void unnormalizedDirectionReturnsTInDirectionLengthUnits() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        // |d|=2：命中点 (0,0,-1) = o + t·d → t = 2（世界位移 4 = t×|d|）
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 2));

        float t = cylinder.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(2.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
    }

    @Test
    void zeroDirectionRayMisses() {
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 0));

        assertEquals(-1.0f, cylinder.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void scaleAbsorptionInvariantKeepsTEqualToWorldDistance() {
        // §4.2 直证（圆柱版）：T·R·S 逆变换射线（direction 不归一化）后 t 恒=世界距离
        CylinderShape cylinder = new CylinderShape(1.0f, 2.0f);
        Matrix4f m = new Matrix4f()
                .translation(3, 0, 0)
                .rotateY((float) Math.toRadians(30.0))
                .scale(2, 3, 4);
        Matrix4f inv = m.invert(new Matrix4f());

        Vector3f worldOrigin = new Vector3f(3, 0, -5);
        Vector3f worldDir = new Vector3f(0, 0, 1);
        Vector3f localOrigin = inv.transformPosition(new Vector3f(worldOrigin));
        Vector3f localDir = inv.transformDirection(new Vector3f(worldDir));
        Ray localRay = new Ray(localOrigin, localDir);

        float t = cylinder.collideWithRay(localRay, hitPoint, hitNormal);

        assertTrue(t >= 0.0f, "旋转+非均匀缩放的逆变换射线应命中圆柱");
        // 本地命中点在侧面上（径向距离=半径），y 在高度范围内
        assertEquals(1.0f, (float) Math.sqrt(hitPoint.x * hitPoint.x + hitPoint.z * hitPoint.z), MATRIX_EPS);
        assertTrue(Math.abs(hitPoint.y) <= 1.0f + MATRIX_EPS, "命中点 y 应在圆柱高度范围内");
        // t = 世界距离（d̂ 单位方向：|M·p_l − o_w|）
        Vector3f worldHit = m.transformPosition(new Vector3f(hitPoint));
        assertEquals(t, worldHit.distance(worldOrigin), MATRIX_EPS);
    }
}
