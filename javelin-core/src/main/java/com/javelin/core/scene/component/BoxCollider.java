package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.physics.Ray;
import com.javelin.core.physics.collision.Aabb;
import com.javelin.core.physics.collision.shape.BoxShape;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import org.joml.Vector3f;

/**
 * 盒碰撞体组件（Engine_Collision_Design.md §6，D7-B：组件字段即形状参数）。
 *
 * <p>size = 全尺寸（默认对齐 Unity (1,1,1)；查询侧折半折算
 * {@code BoxShape.halfExtents}——例程自写 slab，见碰撞档 §4.4 实现期改定）。
 * center 见基类（形状中心在节点本地空间的偏移）。查询时把 size 同步进缓存
 * {@code BoxShape} 实例（每查同步免脏标记），经基类 {@code collideShape}
 * 统一走变换管线。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class BoxCollider extends Collider {

    // 非 final：序列化反序列化需回写（红线 9）
    private Vector3f size = new Vector3f(1.0f, 1.0f, 1.0f);

    // 查询缓存形状（运行态不落盘；参数每查同步）
    private BoxShape shape = new BoxShape();

    public Vector3f getSize() {
        return size;
    }

    public void setSize(Vector3f size) {
        this.size.set(size);
    }

    @Override
    public float collideWithRay(Ray worldRay, Vector3f hitPoint, Vector3f hitNormal) {
        // size（全尺寸）→ halfExtents，每查同步免脏标记
        shape.getHalfExtents().set(size).mul(0.5f);
        return collideShape(shape, worldRay, hitPoint, hitNormal);
    }

    /**
     * 拟合：center = mesh 包围盒中心、size = 包围盒全尺寸（精确贴合；数据源
     * getBounds——set 即算/惰性兜底/上传前钉死，见 Mesh.getBounds 契约）。
     */
    @Override
    public void fitFromMesh(Mesh mesh) {
        Aabb bounds = mesh.getBounds();
        if (bounds == null) {
            throw new IllegalStateException("Mesh 无 POSITION 数据（或 CPU 副本已释放且 bounds 未钉死），无法拟合盒碰撞体");
        }
        getCenter().set(bounds.getMin()).add(bounds.getMax()).mul(0.5f);
        size.set(bounds.getMax()).sub(bounds.getMin());
    }

    /**
     * 序列化数据面：size 直传字段本体（center 在基类，红线 10）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("size", size);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        size = input.readVector3f("size");
    }
}
