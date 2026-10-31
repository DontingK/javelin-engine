package cn.donting.javelin.core.physics.collision;

import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.physics.collision.Aabb;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轴对齐包围盒单测（片③，Engine_Collision_Design.md §5/§7）。
 *
 * <p>覆盖：setFromPoints 点集扫描（单点退化/空输入不变）、transform 行和公式
 * 对照 8 角点极值基准（§5 勘误的直证——两法数学等价）、旋转手算值、out=this
 * 原地写、rayIntersect 包 JOML 的半直线语义（命中区间/内部负 near/身后未命中
 * out 参不变/非归一化方向 t 实长单位）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class AabbTest {

    /** 解析用例浮点容差 */
    private static final float EPS = 1e-4f;

    @Test
    void setFromPointsScansMinMax() {
        Aabb box = new Aabb();
        box.setFromPoints(new float[]{
                -1, -2, -3,
                4, 5, 6,
                0, 0, 0});

        assertEquals(-1, box.getMin().x, EPS);
        assertEquals(-2, box.getMin().y, EPS);
        assertEquals(-3, box.getMin().z, EPS);
        assertEquals(4, box.getMax().x, EPS);
        assertEquals(5, box.getMax().y, EPS);
        assertEquals(6, box.getMax().z, EPS);
    }

    @Test
    void setFromPointsHandlesSinglePointAndDegenerateInput() {
        Aabb box = new Aabb();
        box.setFromPoints(new float[]{1, 2, 3});

        // 单点：退化盒（min == max）
        assertEquals(1, box.getMin().x, EPS);
        assertEquals(2, box.getMin().y, EPS);
        assertEquals(3, box.getMin().z, EPS);
        assertEquals(1, box.getMax().x, EPS);
        assertEquals(2, box.getMax().y, EPS);
        assertEquals(3, box.getMax().z, EPS);

        // null / 空数组：不改变当前盒
        box.setFromPoints(null);
        box.setFromPoints(new float[0]);
        assertEquals(1, box.getMin().x, EPS);
        assertEquals(3, box.getMax().z, EPS);
    }

    @Test
    void transformMatchesEightCornerGroundTruth() {
        // §5 勘误直证：行和公式 == 8 角点极值（非对称盒 × T·R·S 全家桶）
        Aabb box = new Aabb(new Vector3f(0, -1, -0.5f), new Vector3f(2, 1, 0.5f));
        Matrix4f m = new Matrix4f()
                .translation(3, 0, 0)
                .rotateY((float) Math.toRadians(30.0))
                .scale(2, 3, 4);

        // 8 角点对照基准
        float nX = Float.POSITIVE_INFINITY, nY = Float.POSITIVE_INFINITY, nZ = Float.POSITIVE_INFINITY;
        float mX = Float.NEGATIVE_INFINITY, mY = Float.NEGATIVE_INFINITY, mZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            Vector3f corner = new Vector3f(
                    ((i & 1) == 0) ? 0 : 2,
                    ((i & 2) == 0) ? -1 : 1,
                    ((i & 4) == 0) ? -0.5f : 0.5f);
            m.transformPosition(corner);
            nX = Math.min(nX, corner.x);
            mX = Math.max(mX, corner.x);
            nY = Math.min(nY, corner.y);
            mY = Math.max(mY, corner.y);
            nZ = Math.min(nZ, corner.z);
            mZ = Math.max(mZ, corner.z);
        }

        Aabb out = new Aabb();
        box.transform(m, out);
        assertEquals(nX, out.getMin().x, EPS);
        assertEquals(nY, out.getMin().y, EPS);
        assertEquals(nZ, out.getMin().z, EPS);
        assertEquals(mX, out.getMax().x, EPS);
        assertEquals(mY, out.getMax().y, EPS);
        assertEquals(mZ, out.getMax().z, EPS);
    }

    @Test
    void transformRotatedHandCheck() {
        // [−1,1]³ 绕 Y 旋转 45°：世界盒 = [−√2,√2]×[−1,1]×[−√2,√2]
        Aabb box = new Aabb(new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1));
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(45.0));

        Aabb out = new Aabb();
        box.transform(m, out);

        float r2 = (float) Math.sqrt(2.0);
        assertEquals(-r2, out.getMin().x, EPS);
        assertEquals(-1, out.getMin().y, EPS);
        assertEquals(-r2, out.getMin().z, EPS);
        assertEquals(r2, out.getMax().x, EPS);
        assertEquals(1, out.getMax().y, EPS);
        assertEquals(r2, out.getMax().z, EPS);
    }

    @Test
    void transformSupportsInPlaceOut() {
        Aabb box = new Aabb(new Vector3f(0, 0, 0), new Vector3f(2, 2, 2));
        Matrix4f m = new Matrix4f().translation(10, 0, 0);

        box.transform(m, box);

        assertEquals(10, box.getMin().x, EPS);
        assertEquals(0, box.getMin().y, EPS);
        assertEquals(0, box.getMin().z, EPS);
        assertEquals(12, box.getMax().x, EPS);
        assertEquals(2, box.getMax().y, EPS);
        assertEquals(2, box.getMax().z, EPS);
    }

    @Test
    void rayIntersectReportsNearFarInterval() {
        Aabb box = new Aabb(new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1));
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));
        Vector2f nearFar = new Vector2f(9, 9);

        assertTrue(box.rayIntersect(ray, nearFar));
        assertEquals(4, nearFar.x, EPS);
        assertEquals(6, nearFar.y, EPS);
    }

    @Test
    void rayIntersectFromInsideReturnsNegativeNear() {
        Aabb box = new Aabb(new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1));
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 1));
        Vector2f nearFar = new Vector2f();

        // origin 在盒内：true 且 near 为负（区间跨 0）
        assertTrue(box.rayIntersect(ray, nearFar));
        assertEquals(-1, nearFar.x, EPS);
        assertEquals(1, nearFar.y, EPS);
    }

    @Test
    void rayIntersectBehindAndParallelOutsideMissLeavesOutUnchanged() {
        Aabb box = new Aabb(new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1));
        Vector2f nearFar = new Vector2f(9, 9);

        // 盒整体在身后
        assertFalse(box.rayIntersect(new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, 1)), nearFar));
        assertEquals(9, nearFar.x, EPS);
        assertEquals(9, nearFar.y, EPS);

        // 平行 slab 外
        assertFalse(box.rayIntersect(new Ray(new Vector3f(3, 0, -5), new Vector3f(0, 0, 1)), nearFar));
        assertEquals(9, nearFar.x, EPS);
    }

    @Test
    void rayIntersectUnnormalizedDirectionTInDirectionUnits() {
        Aabb box = new Aabb(new Vector3f(-1, -1, -1), new Vector3f(1, 1, 1));
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 2));
        Vector2f nearFar = new Vector2f();

        assertTrue(box.rayIntersect(ray, nearFar));
        // |d|=2：near=2 / far=3（世界位移 4/6 = t×|d|）
        assertEquals(2, nearFar.x, EPS);
        assertEquals(3, nearFar.y, EPS);
    }
}
