package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.physics.Ray;
import com.javelin.core.physics.collision.Aabb;
import com.javelin.core.physics.collision.shape.SphereShape;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import org.joml.Vector3f;

/**
 * 球碰撞体组件（Engine_Collision_Design.md §6，D7-B：组件字段即形状参数）。
 *
 * <p>radius 对齐 Unity 默认 0.5；center 见基类。查询时把 radius 同步进缓存
 * {@code SphereShape} 实例（每查同步免脏标记；例程自写二次方程、不可包 JOML，
 * 见碰撞档 §4.4），经基类 {@code collideShape} 统一走变换管线。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class SphereCollider extends Collider {

    // 非 final：序列化反序列化需回写（红线 9）
    private float radius = 0.5f;

    // 查询缓存形状（运行态不落盘；参数每查同步）
    private SphereShape shape = new SphereShape();

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    @Override
    public float collideWithRay(Ray worldRay, Vector3f hitPoint, Vector3f hitNormal) {
        shape.setRadius(radius);
        return collideShape(shape, worldRay, hitPoint, hitNormal);
    }

    /**
     * 拟合：center = mesh 包围盒中心、radius = 盒半对角线（保守包围；最小包围球
     * 后置；数据源 getBounds——set 即算/惰性兜底/上传前钉死，见 Mesh.getBounds 契约）。
     */
    @Override
    public void fitFromMesh(Mesh mesh) {
        Aabb bounds = mesh.getBounds();
        if (bounds == null) {
            throw new IllegalStateException("Mesh 无 POSITION 数据（或 CPU 副本已释放且 bounds 未钉死），无法拟合球碰撞体");
        }
        getCenter().set(bounds.getMin()).add(bounds.getMax()).mul(0.5f);
        radius = bounds.getMin().distance(bounds.getMax()) * 0.5f;
    }

    /**
     * 序列化数据面：radius 基元（center 在基类，红线 10）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("radius", radius);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        radius = input.readFloat("radius");
    }
}
