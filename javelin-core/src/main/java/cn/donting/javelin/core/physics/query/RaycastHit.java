package cn.donting.javelin.core.physics.query;

import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Collider;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 射线命中结果（L1 场景查询，Engine_Collision_Design.md §10）。
 *
 * <p>point/normal 为复用缓冲（getter 返回内部引用，对齐 Ray 口径，勿缓存跨查询）；
 * distance 与入口 maxDistance 同单位 = <b>世界距离</b>（Physics 门面入口对方向做
 * 一次性归一化）。查询未命中时本对象不变——调用方可复用一个实例承接多次查询零分配，
 * 但判定以查询返回值/计数为准，勿读未命中的旧值。</p>
 *
 * <p>可变数据类，单线程使用（对齐引擎主循环线程模型）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class RaycastHit {

    private final Vector3f point = new Vector3f();
    private final Vector3f normal = new Vector3f();
    private float distance;
    private Collider collider;
    private Node node;

    /** 世界空间命中点（复用缓冲） */
    public Vector3f getPoint() {
        return point;
    }

    /** 世界空间单位法线（复用缓冲） */
    public Vector3f getNormal() {
        return normal;
    }

    /** 命中距离（世界单位） */
    public float getDistance() {
        return distance;
    }

    /** 命中的碰撞体组件 */
    public Collider getCollider() {
        return collider;
    }

    /** 命中节点（= 碰撞体所属节点） */
    public Node getNode() {
        return node;
    }

    /** 查询层回写（Physics 调用；外部勿用） */
    void fill(Vector3fc point, Vector3fc normal, float distance, Collider collider) {
        this.point.set(point);
        this.normal.set(normal);
        this.distance = distance;
        this.collider = collider;
        this.node = collider.getNode();
    }
}
