package com.javelin.core.physics.collision.shape;

import com.javelin.core.physics.Ray;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盒形状相交单测（分片①，Engine_Collision_Design.md §7）。
 *
 * <p>解析解对照：正面/侧面命中（法线轴+符号）、平行 slab 外未命中（out 参不变）、
 * 身后未命中、origin 在盒内出射面命中、非归一化方向 t 以实长为单位、零方向退化
 * 守卫；外加 §4.2 缩放吸收不变量直证（T·R·S 逆变换射线）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BoxShapeTest {

    /** 解析用例浮点容差 */
    private static final float EPS = 1e-4f;

    /** 矩阵链路用例容差（invert/transform 多级舍入） */
    private static final float MATRIX_EPS = 1e-3f;

    // 未命中不变哨兵：验证 out 参复用契约
    private final Vector3f hitPoint = new Vector3f(9, 9, 9);
    private final Vector3f hitNormal = new Vector3f(8, 8, 8);

    @Test
    void rayHitsBoxFrontFace() {
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = box.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void rayParallelOutsideSlabMissesAndLeavesOutParamsUnchanged() {
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        // d.x=0 且 origin.x=3 在 x-slab 之外：平行不相交
        Ray ray = new Ray(new Vector3f(3, 0, -5), new Vector3f(0, 0, 1));

        float t = box.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(-1.0f, t, EPS);
        assertEquals(9.0f, hitPoint.x, EPS);
        assertEquals(9.0f, hitPoint.y, EPS);
        assertEquals(9.0f, hitPoint.z, EPS);
        assertEquals(8.0f, hitNormal.x, EPS);
        assertEquals(8.0f, hitNormal.y, EPS);
        assertEquals(8.0f, hitNormal.z, EPS);
    }

    @Test
    void rayHitsMinusXFaceWithOffset() {
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        // y/z 偏移证明其余 slab 不干扰 x 面判定
        Ray ray = new Ray(new Vector3f(-3, 0.5f, 0.2f), new Vector3f(1, 0, 0));

        float t = box.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(2.0f, t, EPS);
        assertEquals(-1.0f, hitPoint.x, EPS);
        assertEquals(0.5f, hitPoint.y, EPS);
        assertEquals(0.2f, hitPoint.z, EPS);
        assertEquals(-1.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(0.0f, hitNormal.z, EPS);
    }

    @Test
    void boxBehindRayOriginMisses() {
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        // z-slab 区间 [-6,-4] 全负：盒整体在身后
        Ray ray = new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, 1));

        assertEquals(-1.0f, box.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void rayFromInsideBoxHitsExitFace() {
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 1));

        float t = box.collideWithRay(ray, hitPoint, hitNormal);

        // 契约：origin 在盒内 → 出射面命中（t=tFar，法线=出射面）
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
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        // |d|=3：入射 z=-1 → t = 4/3（世界位移 4 = t×|d|）
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 3));

        float t = box.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f / 3.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
    }

    @Test
    void zeroDirectionRayMisses() {
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 0));

        assertEquals(-1.0f, box.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void scaleAbsorptionInvariantKeepsTEqualToWorldDistance() {
        // §4.2 直证（盒版）：T·R·S 逆变换射线（direction 不归一化）后 t 恒=世界距离
        BoxShape box = new BoxShape(new Vector3f(1, 1, 1));
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

        float t = box.collideWithRay(localRay, hitPoint, hitNormal);

        assertTrue(t >= 0.0f, "旋转+非均匀缩放的逆变换射线应命中单位盒");
        // 本地命中点在盒面上：三分量绝对值最大者 = 半尺寸 1
        float maxAbs = Math.max(Math.abs(hitPoint.x), Math.max(Math.abs(hitPoint.y), Math.abs(hitPoint.z)));
        assertEquals(1.0f, maxAbs, MATRIX_EPS);
        // t = 世界距离（d̂ 单位方向：|M·p_l − o_w|）
        Vector3f worldHit = m.transformPosition(new Vector3f(hitPoint));
        assertEquals(t, worldHit.distance(worldOrigin), MATRIX_EPS);
    }
}
