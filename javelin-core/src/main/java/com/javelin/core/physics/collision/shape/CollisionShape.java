package com.javelin.core.physics.collision.shape;

import com.javelin.core.physics.Ray;
import org.joml.Vector3f;

/**
 * 碰撞形状接口（纯数学层，Engine_Collision_Design.md §3）。
 *
 * <p>只负责本地空间的几何相交计算，不感知 ECS/场景/世界空间——上层（L1 场景查询/
 * L2 物理系统）持变换，把射线预先变换到形状本地空间后再调用（§2 三层规矩）。
 * 形状中心恒=本地原点（center 偏移归 Collider 组件，§9）。</p>
 *
 * <p>契约：</p>
 * <ul>
 *   <li><b>无 applyScale</b>：非均匀缩放由射线逆变换吸收（§4.2），形状参数永不被
 *       世界缩放改写——形状实例可安全共享（物理 body 多体共享同一实例）；</li>
 *   <li><b>不继承 Serializable</b>：纯数学不落盘，序列化归 Collider 组件数据面；</li>
 *   <li>direction 可不归一化，t 以实长为单位（§4.1）。</li>
 * </ul>
 *
 * @author javelin
 * @since 1.0
 */
public interface CollisionShape {

    /**
     * 与本地空间射线做相交检测。
     *
     * @param ray       射线（已在形状本地空间；direction 可不归一化）
     * @param hitPoint  输出：本地空间命中点；未命中时不变（调用方可复用缓冲零分配）
     * @param hitNormal 输出：本地空间单位法线；未命中时不变
     * @return 命中 t（以 ray.direction 实长为单位，§4.1）；<b>负值 = 未命中</b>
     */
    float collideWithRay(Ray ray, Vector3f hitPoint, Vector3f hitNormal);
}
