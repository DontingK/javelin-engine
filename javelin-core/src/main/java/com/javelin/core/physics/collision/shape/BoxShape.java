package com.javelin.core.physics.collision.shape;

import com.javelin.core.physics.Ray;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 盒碰撞形状：中心=本地原点、边沿本地轴，半尺寸 {@code halfExtents}
 * （Engine_Collision_Design.md §3）。
 *
 * <p>相交例程<b>自写 slab 法</b>（实现期改定：JOML {@code intersectRayAab} 不回传
 * 命中面轴、需 Vector2f 结果缓冲——自写零分配且法线轴精确；t 契约与 JOML 相同
 * 按 direction 实长计，碰撞档 §4.4）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BoxShape implements CollisionShape {

    private final Vector3f halfExtents = new Vector3f(0.5f);

    public BoxShape() {
    }

    public BoxShape(Vector3fc halfExtents) {
        this.halfExtents.set(halfExtents);
    }

    public Vector3f getHalfExtents() {
        return halfExtents;
    }

    public void setHalfExtents(Vector3fc halfExtents) {
        this.halfExtents.set(halfExtents);
    }

    @Override
    public float collideWithRay(Ray ray, Vector3f hitPoint, Vector3f hitNormal) {
        Vector3f o = ray.getOrigin();
        Vector3f d = ray.getDirection();
        Vector3f he = halfExtents;

        // slab 法：tNear/tFar 为 [tNear, tFar] ∩ 半直线 的命中区间（以 direction 实长为单位）；
        // 命中面轴与符号随区间端点同步记录（棱/角并列命中取先轴 X>Y>Z）
        float tNear = Float.NEGATIVE_INFINITY;
        float tFar = Float.POSITIVE_INFINITY;
        int nearAxis = -1;
        int farAxis = -1;
        float nearSign = 0.0f;
        float farSign = 0.0f;
        boolean anyDirection = false;

        // —— X 轴 slab ——
        if (d.x == 0.0f) {
            if (o.x < -he.x || o.x > he.x) {
                return -1f;
            }
        } else {
            anyDirection = true;
            float inv = 1.0f / d.x;
            float t1 = (-he.x - o.x) * inv;
            float t2 = (he.x - o.x) * inv;
            if (t1 <= t2) {
                if (t1 > tNear) { tNear = t1; nearAxis = 0; nearSign = -1.0f; }
                if (t2 < tFar) { tFar = t2; farAxis = 0; farSign = 1.0f; }
            } else {
                if (t2 > tNear) { tNear = t2; nearAxis = 0; nearSign = 1.0f; }
                if (t1 < tFar) { tFar = t1; farAxis = 0; farSign = -1.0f; }
            }
        }

        // —— Y 轴 slab ——
        if (d.y == 0.0f) {
            if (o.y < -he.y || o.y > he.y) {
                return -1f;
            }
        } else {
            anyDirection = true;
            float inv = 1.0f / d.y;
            float t1 = (-he.y - o.y) * inv;
            float t2 = (he.y - o.y) * inv;
            if (t1 <= t2) {
                if (t1 > tNear) { tNear = t1; nearAxis = 1; nearSign = -1.0f; }
                if (t2 < tFar) { tFar = t2; farAxis = 1; farSign = 1.0f; }
            } else {
                if (t2 > tNear) { tNear = t2; nearAxis = 1; nearSign = 1.0f; }
                if (t1 < tFar) { tFar = t1; farAxis = 1; farSign = -1.0f; }
            }
        }

        // —— Z 轴 slab ——
        if (d.z == 0.0f) {
            if (o.z < -he.z || o.z > he.z) {
                return -1f;
            }
        } else {
            anyDirection = true;
            float inv = 1.0f / d.z;
            float t1 = (-he.z - o.z) * inv;
            float t2 = (he.z - o.z) * inv;
            if (t1 <= t2) {
                if (t1 > tNear) { tNear = t1; nearAxis = 2; nearSign = -1.0f; }
                if (t2 < tFar) { tFar = t2; farAxis = 2; farSign = 1.0f; }
            } else {
                if (t2 > tNear) { tNear = t2; nearAxis = 2; nearSign = 1.0f; }
                if (t1 < tFar) { tFar = t1; farAxis = 2; farSign = -1.0f; }
            }
        }

        if (!anyDirection || tNear > tFar || tFar < 0.0f) {
            // 零方向退化射线 / 区间为空 / 盒整体在身后
            return -1f;
        }
        float t;
        int axis;
        float sign;
        if (tNear >= 0.0f) {
            // 入射面命中
            t = tNear;
            axis = nearAxis;
            sign = nearSign;
        } else {
            // origin 在盒内：出射面命中
            t = tFar;
            axis = farAxis;
            sign = farSign;
        }
        hitPoint.set(d).mul(t).add(o);
        if (axis == 0) {
            hitNormal.set(sign, 0.0f, 0.0f);
        } else if (axis == 1) {
            hitNormal.set(0.0f, sign, 0.0f);
        } else {
            hitNormal.set(0.0f, 0.0f, sign);
        }
        return t;
    }
}
