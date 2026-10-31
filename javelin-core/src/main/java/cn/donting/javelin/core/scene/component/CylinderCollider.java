package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.physics.collision.Aabb;
import cn.donting.javelin.core.physics.collision.shape.CylinderShape;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import org.joml.Vector3f;

/**
 * 圆柱碰撞体组件（Engine_Collision_Design.md §6，D7-B：组件字段即形状参数；
 * 2026-10-05 编辑器移动轴拾取需要——首个消费方）。
 *
 * <p>轴向本地 Y（同 {@code CylinderShape}，随节点旋转指向任意方向）；默认值
 * radius=0.5/height=2 对齐 Unity CapsuleCollider 比例（Unity 无原生圆柱碰撞体）。
 * 查询时参数每查同步进缓存 {@code CylinderShape} 实例，经基类
 * {@code collideShape} 统一走变换管线。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class CylinderCollider extends Collider {

    // 非 final：序列化反序列化需回写（红线 9）
    private float radius = 0.5f;

    private float height = 2.0f;

    // 查询缓存形状（运行态不落盘；参数每查同步）
    private CylinderShape shape = new CylinderShape();

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
    public float collideWithRay(Ray worldRay, Vector3f hitPoint, Vector3f hitNormal) {
        shape.setRadius(radius);
        shape.setHeight(height);
        return collideShape(shape, worldRay, hitPoint, hitNormal);
    }

    /**
     * 拟合：center = mesh 包围盒中心、radius = max(半宽X, 半宽Z)（两横向取大者
     * 保守覆盖）、height = 盒全高（轴向 Y）。
     */
    @Override
    public void fitFromMesh(Mesh mesh) {
        Aabb bounds = mesh.getBounds();
        if (bounds == null) {
            throw new IllegalStateException("Mesh 无 POSITION 数据（或 CPU 副本已释放且 bounds 未钉死），无法拟合圆柱碰撞体");
        }
        getCenter().set(bounds.getMin()).add(bounds.getMax()).mul(0.5f);
        radius = Math.max(bounds.getMax().x - bounds.getMin().x, bounds.getMax().z - bounds.getMin().z) * 0.5f;
        height = bounds.getMax().y - bounds.getMin().y;
    }

    /**
     * 序列化数据面：radius/height 基元（center 在基类，红线 10）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("radius", radius);
        output.write("height", height);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        radius = input.readFloat("radius");
        height = input.readFloat("height");
    }
}
