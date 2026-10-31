package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.physics.collision.shape.CollisionShape;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 碰撞体组件抽象基类（拍板 2026-10-05：碰撞体=组件、负责形状，Engine_Collision_Design.md §6）。
 *
 * <p>挂节点的场景面（authoring/序列化/生命周期）；形状是引擎内部纯数学
 * （CollisionShape，D8：不继承 Component）——子类字段即形状参数（D7-B），查询时
 * 同步进各自缓存的形状实例后经 {@link #collideShape} 统一走变换管线。
 * {@code center}=形状中心在节点本地空间的偏移（Unity 口径全碰撞体共有，收在基类；
 * 形状中心恒=本地原点）。</p>
 *
 * <p>无 Rigidbody 的 Collider = 静态碰撞体/纯查询体（拾取可用，不进动力学积分）；
 * Rigidbody 产生接触需自身或子节点上至少一个 Collider。裸组件（未挂节点）不参与
 * 查询——collideWithRay 返 -1（查询层仍按场景组件表遍历保证）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class Collider extends Component {

    // 非 final：序列化反序列化需回写（红线 9）
    private Vector3f center = new Vector3f();

    // 查询管线内部缓冲（运行态不落盘、单线程每查复用零分配；对齐 Transform 缓存字段口径）
    private Matrix4f invMatrix = new Matrix4f();
    private Matrix4f normalMatrix = new Matrix4f();
    private Ray localRay = new Ray();

    public Vector3f getCenter() {
        return center;
    }

    public void setCenter(Vector3f center) {
        this.center.set(center);
    }

    /**
     * 与世界空间射线做相交检测（拾取/查询的组件级入口）。
     *
     * <p>实现走 {@link #collideShape} 统一管线（片②）：取节点
     * {@code Transform.getLocalToWorld()} 为 M，射线经 M⁻¹（= S⁻¹R⁻¹T⁻¹，
     * <b>旋转随逆矩阵自然处理</b>）变换到形状本地空间——origin 点变换、
     * direction 向量变换且<b>不归一化</b>（§4.2 缩放吸收：t 恰为世界方向
     * 实长单位），center 偏移在此扣除；委托形状例程后命中量回换世界：
     * hitPoint = M·p_l，hitNormal 走法线矩阵（(M⁻¹)ᵀ，含旋转与缩放）变换后
     * 归一化。</p>
     *
     * @param worldRay  世界空间射线（direction 归一化时返回值即世界距离）
     * @param hitPoint  输出：世界空间命中点；未命中时不变（可复用缓冲）
     * @param hitNormal 输出：世界空间单位法线；未命中时不变
     * @return 命中 t（以 worldRay.direction 实长为单位）；负值 = 未命中
     */
    public abstract float collideWithRay(Ray worldRay, Vector3f hitPoint, Vector3f hitNormal);

    /**
     * 形状委托统一管线（片②实现，子类把参数同步进缓存形状实例后调用）：
     * 逆矩阵变换射线（M⁻¹ = S⁻¹R⁻¹T⁻¹——<b>旋转随逆矩阵自然处理</b>；
     * origin 点变换后扣 center；direction 向量变换不归一化——§4.2）→
     * 形状例程 → 命中量回世界（hitPoint 加 center 后经 M 变换；hitNormal
     * 经法线矩阵 (M⁻¹)ᵀ 变换后归一化——旋转与非均匀缩放下皆正确）。
     *
     * <p>t 直传（§4.2 不变量：与 center 平移无关，M·p_l = o_w + t·d_w）。
     * 每查复用内部缓冲零分配；不修改调用方射线与节点缓存矩阵。</p>
     *
     * @param shape     已同步参数的形状实例（子类缓存持有）
     * @param worldRay  世界射线
     * @param hitPoint  输出：世界空间命中点；未命中时不变
     * @param hitNormal 输出：世界空间单位法线；未命中时不变
     * @return 命中 t（以 worldRay.direction 实长为单位）；负值 = 未命中
     */
    protected final float collideShape(CollisionShape shape, Ray worldRay, Vector3f hitPoint, Vector3f hitNormal) {
        if (getNode() == null) {
            return -1f;
        }
        Matrix4f world = getNode().getTransform().getLocalToWorld();
        world.invert(invMatrix);
        world.normal(normalMatrix);
        // 世界射线 → 形状本地（M⁻¹ 含 R⁻¹S⁻¹——旋转缩放一并处理）：
        // origin 点逆变换后扣 center 偏移；direction 向量逆变换不归一化
        localRay.set(worldRay.getOrigin(), worldRay.getDirection());
        invMatrix.transformPosition(localRay.getOrigin());
        invMatrix.transformDirection(localRay.getDirection());
        localRay.getOrigin().sub(center);

        float t = shape.collideWithRay(localRay, hitPoint, hitNormal);
        if (t < 0.0f) {
            return t;
        }
        // 命中量回世界：点加 center 回组件本地再经 M 变换；法线走法线矩阵后归一化
        hitPoint.add(center);
        world.transformPosition(hitPoint);
        normalMatrix.transformDirection(hitNormal);
        hitNormal.normalize();
        return t;
    }

    /**
     * 按 Mesh 自动拟合形状参数（authoring 便利，碰撞档 D9：归组件层不归形状层——
     * 纯数学不识资产类，且拟合产物 center+参数两件归组件字段）。
     *
     * <p>按 mesh 本地空间包围数据写 center 与形状参数（mesh 本地即节点本地——
     * MeshRenderer 无额外网格变换）。数据源实现期定：POSITION 属性（CPU 副本
     * 未释放时）或 Mesh.bounds 缓存（分片③ 落地后统一走 bounds——上传释放
     * CPU 副本后仍可用）。Box 精确贴合包围盒；Sphere 取保守包围（中心=盒心、
     * 半径=盒半对角线；最小包围球后置）；Cylinder 取 max(半宽X, 半宽Z) 为半径、
     * 全高为高（轴向 Y）。</p>
     *
     * @param mesh 源网格
     */
    public abstract void fitFromMesh(Mesh mesh);

    /**
     * 序列化数据面：center（子类 write 追加各自形状参数）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("center", center);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        center = input.readVector3f("center");
    }
}
