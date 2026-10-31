package cn.donting.javelin.core.physics.collision.shape;

import cn.donting.javelin.core.physics.Ray;
import org.joml.Vector3f;

/**
 * 圆柱碰撞形状：中心=本地原点，轴向本地 Y，半径 {@code radius}、高 {@code height}
 * （Engine_Collision_Design.md §3）。
 *
 * <p>相交例程自研（JOML 无射线-圆柱例程，§4.4；2026-10-05 用户提前派发）：
 * 无限圆柱二次方程的根按 y ∈ [−h/2, h/2] 裁剪 + 上下端盖平面根按盘内判定，
 * 取 <b>t ≥ 0 的最小边界穿越点</b>（凸体统一语义：外部=入射点、origin 在内部=
 * 出射点）；侧面/端盖交界命中取侧面法线（判定顺序侧→盖）。t 契约同
 * {@link CollisionShape}。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class CylinderShape implements CollisionShape {

    private float radius;

    private float height;

    public CylinderShape() {
    }

    public CylinderShape(float radius, float height) {
        this.radius = radius;
        this.height = height;
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    @Override
    public float collideWithRay(Ray ray, Vector3f hitPoint, Vector3f hitNormal) {
        Vector3f o = ray.getOrigin();
        Vector3f d = ray.getDirection();
        float halfHeight = height * 0.5f;

        // 命中面：1=侧面（法线径向） 2=顶盖(+Y) 3=底盖(−Y)——取 t≥0 最小边界穿越点
        float bestT = -1.0f;
        int hitKind = 0;

        // —— 侧面：无限圆柱 x²+z²=r²，根按 y 范围裁剪 ——
        float a = d.x * d.x + d.z * d.z;
        if (a > 0.0f) {
            float b = 2.0f * (o.x * d.x + o.z * d.z);
            float c = o.x * o.x + o.z * o.z - radius * radius;
            float disc = b * b - 4.0f * a * c;
            if (disc >= 0.0f) {
                float sqrtDisc = (float) Math.sqrt(disc);
                float t0 = (-b - sqrtDisc) / (2.0f * a);
                float t1 = (-b + sqrtDisc) / (2.0f * a);
                for (int root = 0; root < 2; root++) {
                    float t = root == 0 ? t0 : t1;
                    float y = o.y + t * d.y;
                    if (t >= 0.0f && y >= -halfHeight && y <= halfHeight
                            && (bestT < 0.0f || t < bestT)) {
                        bestT = t;
                        hitKind = 1;
                    }
                }
            }
        }

        // —— 端盖：y=±halfHeight 平面根按 x²+z²≤r² 判盘内 ——
        if (d.y != 0.0f) {
            float invY = 1.0f / d.y;
            float t = (halfHeight - o.y) * invY;
            float x = o.x + t * d.x;
            float z = o.z + t * d.z;
            if (t >= 0.0f && x * x + z * z <= radius * radius
                    && (bestT < 0.0f || t < bestT)) {
                bestT = t;
                hitKind = 2;
            }
            t = (-halfHeight - o.y) * invY;
            x = o.x + t * d.x;
            z = o.z + t * d.z;
            if (t >= 0.0f && x * x + z * z <= radius * radius
                    && (bestT < 0.0f || t < bestT)) {
                bestT = t;
                hitKind = 3;
            }
        }

        // 零方向退化射线：a=0 且 d.y=0，两类候选皆不产生，自然未命中
        if (hitKind == 0) {
            return -1f;
        }
        hitPoint.set(d).mul(bestT).add(o);
        if (hitKind == 1) {
            hitNormal.set(hitPoint.x, 0.0f, hitPoint.z).normalize();
        } else if (hitKind == 2) {
            hitNormal.set(0.0f, 1.0f, 0.0f);
        } else {
            hitNormal.set(0.0f, -1.0f, 0.0f);
        }
        return bestT;
    }
}
