package com.javelin.core.physics.collision;

import com.javelin.core.physics.Ray;
import org.joml.Intersectionf;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 轴对齐包围盒（纯数学层，Engine_Collision_Design.md §3/§5）。
 *
 * <p>保守粗筛语义，<b>不是精确碰撞几何</b>——不实现 {@code CollisionShape}；
 * 用途：拾取粗筛（Mesh.bounds）、视锥剔除、物理 broadphase 的世界包围盒源。
 * min/max 可变数据类，单线程使用。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class Aabb {

    private final Vector3f min = new Vector3f();

    private final Vector3f max = new Vector3f();

    // 变换内部缓冲（单线程每查复用零分配）
    private final Vector3f center = new Vector3f();

    public Aabb() {
    }

    public Aabb(Vector3fc min, Vector3fc max) {
        set(min, max);
    }

    public Aabb(Aabb other) {
        set(other);
    }

    public Vector3f getMin() {
        return min;
    }

    public Vector3f getMax() {
        return max;
    }

    public Aabb set(Vector3fc min, Vector3fc max) {
        this.min.set(min);
        this.max.set(max);
        return this;
    }

    public Aabb set(Aabb other) {
        min.set(other.min);
        max.set(other.max);
        return this;
    }

    /**
     * 从连续 xyz 三元组扫出包围盒（Mesh.bounds 数据源，§5）。
     *
     * @param positions 顶点位置数组（每 3 个浮点一个顶点）；null 或长度 &lt; 3 时不改变当前盒
     */
    public Aabb setFromPoints(float[] positions) {
        if (positions == null || positions.length < 3) {
            return this;
        }
        float nX = positions[0], nY = positions[1], nZ = positions[2];
        float mX = nX, mY = nY, mZ = nZ;
        for (int i = 3; i + 3 <= positions.length; i += 3) {
            nX = Math.min(nX, positions[i]);
            mX = Math.max(mX, positions[i]);
            nY = Math.min(nY, positions[i + 1]);
            mY = Math.max(mY, positions[i + 1]);
            nZ = Math.min(nZ, positions[i + 2]);
            mZ = Math.max(mZ, positions[i + 2]);
        }
        min.set(nX, nY, nZ);
        max.set(mX, mY, mZ);
        return this;
    }

    /**
     * 世界盒（精确）：中心经 {@code m} 变换，半尺寸按 |M₃ₓ₃| 行绝对值加权和展开。
     *
     * <p>实现期勘误（碰撞档 §5）：对盒子，8 角点极值与行和公式<b>数学等价</b>
     * （盒=区间积，各输出轴极值恒为 中心′ᵢ ± Σ|M_ij|·he_j）——原「精确/保守
     * 两档」设计不成立，行和即精确值且更便宜（7 次矩阵-向量积之差）；
     * 8 角点仅作单测对照基准。</p>
     *
     * @param m   本地到世界矩阵
     * @param out 输出盒（可为 this）
     */
    public Aabb transform(Matrix4fc m, Aabb out) {
        center.set((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f);
        m.transformPosition(center);
        float hX = (max.x - min.x) * 0.5f;
        float hY = (max.y - min.y) * 0.5f;
        float hZ = (max.z - min.z) * 0.5f;
        float eX = Math.abs(m.m00()) * hX + Math.abs(m.m01()) * hY + Math.abs(m.m02()) * hZ;
        float eY = Math.abs(m.m10()) * hX + Math.abs(m.m11()) * hY + Math.abs(m.m12()) * hZ;
        float eZ = Math.abs(m.m20()) * hX + Math.abs(m.m21()) * hY + Math.abs(m.m22()) * hZ;
        out.min.set(center.x - eX, center.y - eY, center.z - eZ);
        out.max.set(center.x + eX, center.y + eY, center.z + eZ);
        return out;
    }

    /**
     * 射线相交（包 JOML {@code intersectRayAab} slab 法，§4.4——按 direction
     * 实长计 t 可直接包；已核 1.10.8 源码：返回条件 tNear &lt; tFar 且 tFar ≥ 0
     * 即半直线语义，origin 在盒内返回 true 且 near 为负；边缘恰掠过
     * tNear==tFar 判 false——粗筛安全侧可接受）。
     *
     * @param ray     射线（与本盒同一空间；direction 可不归一化）
     * @param nearFar 输出：命中 t 区间（near/far，以 ray.direction 实长为单位，§4.1）；
     *                未命中时不变（调用方持有缓冲零分配）
     * @return 是否相交（半直线语义）
     */
    public boolean rayIntersect(Ray ray, Vector2f nearFar) {
        return Intersectionf.intersectRayAab(ray.getOrigin(), ray.getDirection(), min, max, nearFar);
    }
}
