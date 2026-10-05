package com.javelin.core.physics;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 射线（纯数学层，Engine_Collision_Design.md §3/§4）。
 *
 * <p>t 契约（§4.1）：命中点 = origin + t·direction，<b>t 以 direction 实长为单位</b>，
 * direction 不强制归一化——经逆矩阵变换到形状本地空间的方向天然非单位，此时 t 恰
 * 等于世界距离（§4.2 缩放吸收不变量）。半直线语义：t &ge; 0 才算命中，负 t = 目标
 * 在射线身后 = 未命中。</p>
 *
 * <p>可变数据类，单线程使用（对齐引擎主循环线程模型）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class Ray {

    private final Vector3f origin = new Vector3f();

    private final Vector3f direction = new Vector3f();

    public Ray() {
    }

    public Ray(Vector3fc origin, Vector3fc direction) {
        set(origin, direction);
    }

    public Ray(Ray other) {
        set(other);
    }

    public Vector3f getOrigin() {
        return origin;
    }

    public Vector3f getDirection() {
        return direction;
    }

    public Ray set(Vector3fc origin, Vector3fc direction) {
        this.origin.set(origin);
        this.direction.set(direction);
        return this;
    }

    public Ray set(Ray other) {
        origin.set(other.origin);
        direction.set(other.direction);
        return this;
    }
}
