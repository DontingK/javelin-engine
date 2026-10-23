package com.javelin.editor.debug;

import com.javelin.core.math.Color;
import com.javelin.core.physics.collision.Aabb;
import org.joml.Vector3fc;

/**
 * DebugDrawLine 静态门面（Editor_DebugLine_Design，编辑器专属世界空间调试线——
 * 选中物体 bounds / 相机视锥线框等，Unity Gizmos.DrawLine 语义）。
 *
 * <p>线段端点一律<b>世界空间</b>（旋转/缩放由提交方用节点世界矩阵吸收，门面不感知
 * 变换）；持续到 {@link #clear()}（DebugText 同语义），编辑器消费方每帧
 * clear + 重提交——物体移动/选中变更下一帧即跟随。bind/unbind 由
 * {@link DebugLineAppState} 驱动；未初始化抛 {@link IllegalStateException}
 * （静默会掩盖接线错误）。v0.1 仅引擎线程。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
public final class DebugDrawLine {

    private static DebugLineBatch batch;

    private DebugDrawLine() {
    }

    /** 绑定数据面（DebugLineAppState onAttach；重复绑定抛错防双挂） */
    static void bind(DebugLineBatch target) {
        if (batch != null) {
            throw new IllegalStateException("DebugDrawLine 已绑定（重复装配？）");
        }
        batch = target;
    }

    /** 解绑（DebugLineAppState onDetach） */
    static void unbind() {
        batch = null;
    }

    /** 清空全部线段（每帧 clear + 重提交惯用法） */
    public static void clear() {
        bound().clear();
    }

    /**
     * 提交一条线段（世界空间端点）。
     *
     * @param from  起点
     * @param to    终点
     * @param color 线色（RGBA）
     */
    public static void line(Vector3fc from, Vector3fc to, Color color) {
        bound().addLine(from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), color);
    }

    /** 提交一个世界空间包围盒线框（12 条边）。 */
    public static void aabb(Aabb worldAabb, Color color) {
        bound().addAabb(worldAabb, color);
    }

    /** 当前线数（截断观测/测试用） */
    public static int lineCount() {
        return bound().vertexCount() / 2;
    }

    private static DebugLineBatch bound() {
        if (batch == null) {
            throw new IllegalStateException("DebugDrawLine 未初始化（DebugLineAppState 未装配）");
        }
        return batch;
    }
}
