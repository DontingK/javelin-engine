package com.javelin.core.physics.collision.shape;

import com.javelin.core.physics.Ray;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 球形状相交单测（分片①，Engine_Collision_Design.md §7）。
 *
 * <p>解析解对照：命中/未命中（out 参不变）/切线掠过/身后（半直线语义）/origin
 * 在球内出射命中/非归一化方向 t 以实长为单位/零方向退化守卫；外加 §4.2 缩放吸收
 * 不变量直证——T·R·S 逆变换射线（direction 不归一化）后 t 恒=世界距离。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class SphereShapeTest {

    /** 解析用例浮点容差 */
    private static final float EPS = 1e-4f;

    /** 矩阵链路用例容差（invert/transform 多级舍入） */
    private static final float MATRIX_EPS = 1e-3f;

    // 未命中不变哨兵：验证 out 参复用契约
    private final Vector3f hitPoint = new Vector3f(9, 9, 9);
    private final Vector3f hitNormal = new Vector3f(8, 8, 8);

    @Test
    void rayHitsSphereFromFront() {
        SphereShape sphere = new SphereShape(1.0f);
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = sphere.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void rayMissesSphereLeavesOutParamsUnchanged() {
        SphereShape sphere = new SphereShape(1.0f);
        // 与射线所在 z 轴距离 2 > 半径 1：平行掠过不命中
        Ray ray = new Ray(new Vector3f(2, 0, -5), new Vector3f(0, 0, 1));

        float t = sphere.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(-1.0f, t, EPS);
        assertEquals(9.0f, hitPoint.x, EPS);
        assertEquals(9.0f, hitPoint.y, EPS);
        assertEquals(9.0f, hitPoint.z, EPS);
        assertEquals(8.0f, hitNormal.x, EPS);
        assertEquals(8.0f, hitNormal.y, EPS);
        assertEquals(8.0f, hitNormal.z, EPS);
    }

    @Test
    void rayTangentToSphereStillCountsAsHit() {
        SphereShape sphere = new SphereShape(1.0f);
        // 判别式恰为零（整数运算精确）：相切于 (1,0,0)
        Ray ray = new Ray(new Vector3f(1, 0, -5), new Vector3f(0, 0, 1));

        float t = sphere.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(5.0f, t, EPS);
        assertEquals(1.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(0.0f, hitPoint.z, EPS);
        assertEquals(1.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(0.0f, hitNormal.z, EPS);
    }

    @Test
    void sphereBehindRayOriginMisses() {
        SphereShape sphere = new SphereShape(1.0f);
        // 所在直线与球相交但两根皆负：半直线语义 = 未命中
        Ray ray = new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, 1));

        float t = sphere.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(-1.0f, t, EPS);
    }

    @Test
    void rayFromInsideSphereHitsExitPoint() {
        SphereShape sphere = new SphereShape(1.0f);
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 1));

        float t = sphere.collideWithRay(ray, hitPoint, hitNormal);

        // 契约：origin 在球内 → 出射点命中（t=远根）
        assertEquals(1.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(1.0f, hitNormal.z, EPS);
    }

    @Test
    void unnormalizedDirectionReturnsTInDirectionLengthUnits() {
        SphereShape sphere = new SphereShape(1.0f);
        // |d|=2：命中点 (0,0,-1) = o + t·d → t = 2（世界位移 4 = t×|d|）
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 2));

        float t = sphere.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(2.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
    }

    @Test
    void zeroDirectionRayMisses() {
        SphereShape sphere = new SphereShape(1.0f);
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 0));

        assertEquals(-1.0f, sphere.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void scaleAbsorptionInvariantKeepsTEqualToWorldDistance() {
        // §4.2 直证：d_l = M⁻¹·d̂（不归一化）时，本地求得的 t 恒=世界距离，
        // 与 M 藏了什么旋转/非均匀缩放无关。世界侧独立验算：t == |M·p_l − o_w|。
        SphereShape sphere = new SphereShape(1.0f);
        Matrix4f m = new Matrix4f()
                .translation(3, 0, 0)
                .rotateY((float) Math.toRadians(30.0))
                .scale(2, 3, 4);
        Matrix4f inv = m.invert(new Matrix4f());

        Vector3f worldOrigin = new Vector3f(3, 0, -5);
        Vector3f worldDir = new Vector3f(0, 0, 1);
        // 世界射线 → 形状本地：origin 点变换、direction 向量变换且不归一化
        Vector3f localOrigin = inv.transformPosition(new Vector3f(worldOrigin));
        Vector3f localDir = inv.transformDirection(new Vector3f(worldDir));
        Ray localRay = new Ray(localOrigin, localDir);

        float t = sphere.collideWithRay(localRay, hitPoint, hitNormal);

        assertTrue(t >= 0.0f, "旋转+非均匀缩放的逆变换射线应命中单位球");
        // 本地命中点在单位球面上
        assertEquals(1.0f, hitPoint.length(), MATRIX_EPS);
        // t = 世界距离（d̂ 单位方向：|M·p_l − o_w|）
        Vector3f worldHit = m.transformPosition(new Vector3f(hitPoint));
        assertEquals(t, worldHit.distance(worldOrigin), MATRIX_EPS);
    }
}
