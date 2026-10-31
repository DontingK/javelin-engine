package cn.donting.javelin.core.physics.collision.shape;

import cn.donting.javelin.core.physics.Ray;
import org.joml.Vector3f;

/**
 * 球碰撞形状：中心=本地原点，半径 {@code radius}（Engine_Collision_Design.md §3）。
 *
 * <p>相交例程<b>自写判别式二次方程</b>（a=d·d、b=2·L·d、c=L·L−r²），不能包
 * JOML {@code intersectRaySphere}——其 tca/thc 变体只对归一化方向成立（thc 不随
 * |d| 缩放，非归一化方向 t 直接算错），不满足本层 t 契约（§4.4，已核 1.10.8 源码）；
 * a 随 |d| 平方缩放，t 天然以 direction 实长为单位。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class SphereShape implements CollisionShape {

    private float radius = 0.5f;

    public SphereShape() {
    }

    public SphereShape(float radius) {
        this.radius = radius;
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    @Override
    public float collideWithRay(Ray ray, Vector3f hitPoint, Vector3f hitNormal) {
        Vector3f o = ray.getOrigin();
        Vector3f d = ray.getDirection();
        // a = d·d 随 |d| 平方缩放，t 天然以 direction 实长为单位（§4.4）
        float a = d.lengthSquared();
        if (a == 0.0f) {
            return -1f;
        }
        // 判别式二次方程：o 为球心到射线原点的偏移（球心=本地原点）
        float b = 2.0f * o.dot(d);
        float c = o.lengthSquared() - radius * radius;
        float disc = b * b - 4.0f * a * c;
        if (disc < 0.0f) {
            return -1f;
        }
        float sqrtDisc = (float) Math.sqrt(disc);
        float t0 = (-b - sqrtDisc) / (2.0f * a);
        float t1 = (-b + sqrtDisc) / (2.0f * a);
        // 半直线：近根非负优先；t0<0≤t1 = origin 在球内 → 出射点命中；双负=身后未命中
        float t;
        if (t0 >= 0.0f) {
            t = t0;
        } else if (t1 >= 0.0f) {
            t = t1;
        } else {
            return -1f;
        }
        hitPoint.set(d).mul(t).add(o);
        hitNormal.set(hitPoint).normalize();
        return t;
    }
}
