package com.javelin.core.physics.query;

import com.javelin.core.physics.Ray;
import com.javelin.core.scene.LayerMask;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.Collider;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;

/**
 * 物理查询静态门面（L1 场景级 raycast，Engine_Collision_Design.md §10；拍板 2026-10-28）。
 *
 * <p>查询目标 = 绑定场景的 Collider 注册表（{@link Scene#getColliders()}）：跳过
 * 未激活/已禁用碰撞体 → layerMask 过滤 → 取最近命中；maxDistance 为<b>查询参数</b>
 * （Unity 口径——射程不归射线本体）；distance/maxDistance 一律<b>世界距离</b>
 * （入口对方向做一次性归一化，同 Unity Ray 语义）。未命中返回 false/0 且 out 不变
 *（L0 out 参约定）；场景未绑定（引擎未启动/未 setScene）抛 IllegalStateException
 * ——不静默返空（错误显性化）。</p>
 *
 * <p>可变静态缓冲、单线程使用（对齐引擎主循环线程模型，L0 同款口径）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public final class Physics {

    /** 当前查询场景（引擎接线绑定：JavelinEngine.setScene/shutdown；测试可直接直绑） */
    private static Scene scene;

    /** 单次查询复数（单线程复用零分配；raycastAll 逐条拷贝进新实例） */
    private static final Ray scratchRay = new Ray();
    private static final Vector3f scratchPoint = new Vector3f();
    private static final Vector3f scratchNormal = new Vector3f();

    private static final Comparator<RaycastHit> BY_DISTANCE = Comparator.comparingDouble(RaycastHit::getDistance);

    private Physics() {
    }

    /**
     * 绑定/解绑查询场景（引擎接线用：JavelinEngine.setScene 绑定、shutdown 传 null）。
     *
     * @param scene 当前场景；null = 解绑（此后查询抛 IllegalStateException）
     */
    public static void setScene(Scene scene) {
        Physics.scene = scene;
    }

    /**
     * 最近命中（无限距离、不过滤层）。
     *
     * @param ray 世界射线（direction 无需归一化——入口内部归一化）
     * @param out 输出：命中结果；未命中不变（可复用实例零分配）
     * @return 是否命中
     * @throws IllegalStateException 场景未绑定
     */
    public static boolean raycast(Ray ray, RaycastHit out) {
        return raycast(ray, Float.POSITIVE_INFINITY, LayerMask.everything(), out);
    }

    /**
     * 最近命中（限距、不过滤层）。
     *
     * @param ray         世界射线（direction 无需归一化）
     * @param maxDistance 最大命中距离（世界单位，含边界）
     * @param out         输出：命中结果；未命中不变
     * @return 是否命中
     * @throws IllegalStateException 场景未绑定
     */
    public static boolean raycast(Ray ray, float maxDistance, RaycastHit out) {
        return raycast(ray, maxDistance, LayerMask.everything(), out);
    }

    /**
     * 最近命中（全参）。
     *
     * @param ray         世界射线（direction 无需归一化）
     * @param maxDistance 最大命中距离（世界单位，含边界）
     * @param layerMask   层级掩码（{@link LayerMask} 口径；不匹配的节点跳过）
     * @param out         输出：命中结果；未命中不变
     * @return 是否命中
     * @throws IllegalStateException 场景未绑定
     */
    public static boolean raycast(Ray ray, float maxDistance, int layerMask, RaycastHit out) {
        Scene current = boundScene();
        prepareRay(ray);
        List<Collider> colliders = current.getColliders();
        float bestT = Float.POSITIVE_INFINITY;
        boolean hit = false;
        for (int i = 0; i < colliders.size(); i++) {
            Collider collider = colliders.get(i);
            if (!queryable(collider, layerMask)) {
                continue;
            }
            float t = collider.collideWithRay(scratchRay, scratchPoint, scratchNormal);
            // 有限值闸门一并兜住 NaN（零方向归一化得 NaN 方向）/±Inf 退化
            //（BoxShape 对 NaN 方向会走 tFar=+Inf 分支——Inf > maxDistance(∞) 为 false，
            // 光靠限距判据挡不住）
            if (!Float.isFinite(t) || t < 0f || t > maxDistance || t >= bestT) {
                continue;
            }
            bestT = t;
            hit = true;
            out.fill(scratchPoint, scratchNormal, t, collider);
        }
        return hit;
    }

    /**
     * 全部命中（无限距离、不过滤层）。
     *
     * @param ray 世界射线（direction 无需归一化）
     * @param out 输出：命中列表——调用方提供实例，<b>方法内 clear 后按距离升序填充</b>
     * @return 命中条目数
     * @throws IllegalStateException 场景未绑定
     */
    public static int raycastAll(Ray ray, List<RaycastHit> out) {
        return raycastAll(ray, Float.POSITIVE_INFINITY, LayerMask.everything(), out);
    }

    /**
     * 全部命中（限距、不过滤层）。
     *
     * @param ray         世界射线（direction 无需归一化）
     * @param maxDistance 最大命中距离（世界单位，含边界）
     * @param out         输出：命中列表（clear 后按距离升序填充）
     * @return 命中条目数
     * @throws IllegalStateException 场景未绑定
     */
    public static int raycastAll(Ray ray, float maxDistance, List<RaycastHit> out) {
        return raycastAll(ray, maxDistance, LayerMask.everything(), out);
    }

    /**
     * 全部命中（全参）。每条命中为新建 RaycastHit（点/法线各自持有副本）——
     * 与单命中路径不同，本路径仅列表本身可复用，条目按调用方需要持有。
     *
     * @param ray         世界射线（direction 无需归一化）
     * @param maxDistance 最大命中距离（世界单位，含边界）
     * @param layerMask   层级掩码（{@link LayerMask} 口径；不匹配的节点跳过）
     * @param out         输出：命中列表（clear 后按距离升序填充）
     * @return 命中条目数
     * @throws IllegalStateException 场景未绑定
     */
    public static int raycastAll(Ray ray, float maxDistance, int layerMask, List<RaycastHit> out) {
        Scene current = boundScene();
        prepareRay(ray);
        out.clear();
        List<Collider> colliders = current.getColliders();
        for (int i = 0; i < colliders.size(); i++) {
            Collider collider = colliders.get(i);
            if (!queryable(collider, layerMask)) {
                continue;
            }
            float t = collider.collideWithRay(scratchRay, scratchPoint, scratchNormal);
            if (!Float.isFinite(t) || t < 0f || t > maxDistance) {
                continue;
            }
            RaycastHit hit = new RaycastHit();
            hit.fill(scratchPoint, scratchNormal, t, collider);
            out.add(hit);
        }
        out.sort(BY_DISTANCE);
        return out.size();
    }

    // ═══ 内部 ═══

    /** 入口一次性归一化：门面契约 distance/maxDistance 一律世界距离（Unity Ray 语义） */
    private static void prepareRay(Ray ray) {
        scratchRay.set(ray);
        scratchRay.getDirection().normalize();
    }

    /** 候选过滤：激活态（isActiveAndEnabled 内含节点层级判据，node null 亦兜住）+ layerMask */
    private static boolean queryable(Collider collider, int layerMask) {
        return collider.isActiveAndEnabled()
                && LayerMask.contains(layerMask, collider.getNode().getLayer());
    }

    private static Scene boundScene() {
        Scene current = scene;
        if (current == null) {
            throw new IllegalStateException("Physics 场景未绑定：引擎未启动/未 setScene（测试可用 Physics.setScene 直绑）");
        }
        return current;
    }
}
