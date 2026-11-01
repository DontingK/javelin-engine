package cn.donting.javelin.core.render;

import cn.donting.javelin.core.physics.collision.Aabb;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 主光阴影正交视锥构造（Engine_Lighting_Design §4.5 批次④，纯逻辑无 GPU 依赖）。
 *
 * <p>光空间正交基取 JOML lookAt 同式（dir=eye-center、left=up×dir、upn=dir×left），
 * 世界 AABB 在三轴上的行和投影半宽定盒；up 稳定轴防退化（|f·Y| 近 1 换侧轴），
 * Unity 式投影稳定化后置。zZeroToOne=true（Vulkan NDC z [0,1]）、Y 不翻——
 * shadowMap 自写自消费变换自洽，不与主相机位姿约定耦合。</p>
 */
public final class ShadowMath {

    /** 光侧包围盒外放余量（世界单位）：eye 离盒近面距离 + far 平面同值余量 */
    static final float BOX_MARGIN = 0.5f;
    /** up 稳定轴倾斜阈值：|f·Y| ≥ 此值换 (1,0,0) 防退化 */
    static final float UP_SWITCH = 0.999f;
    /** 轴向半宽下限（防退化盒矩阵除零/NaN） */
    private static final float MIN_EXTENT = 0.05f;

    private ShadowMath() {
    }

    /**
     * 构造主光 世界→光裁剪 正交矩阵（单张硬影口径：正交盒盖全场景）。
     *
     * @param worldDir   主光世界传播方向（单位向量，光沿此照）
     * @param bounds     shadowCasters 世界 AABB 并集
     * @param out        输出矩阵（identity 起算）
     * @return out
     */
    public static Matrix4f buildSunViewProj(Vector3f worldDir, Aabb bounds, Matrix4f out) {
        // 光空间正交基：Z = -f（+Z 指向光），left = upHint × Z，upn = Z × left
        Vector3f axisZ = new Vector3f(worldDir).negate();
        float upHintX = 0.0f, upHintY = 1.0f, upHintZ = 0.0f;
        if (Math.abs(worldDir.y) >= UP_SWITCH) {
            upHintX = 1.0f;
            upHintY = 0.0f;
        }
        Vector3f axisX = new Vector3f(upHintX, upHintY, upHintZ).cross(axisZ).normalize();
        Vector3f axisY = new Vector3f(axisZ).cross(axisX).normalize();

        Vector3f min = bounds.getMin();
        Vector3f max = bounds.getMax();
        float cx = (min.x + max.x) * 0.5f, cy = (min.y + max.y) * 0.5f, cz = (min.z + max.z) * 0.5f;
        float hx = Math.abs(max.x - min.x) * 0.5f;
        float hy = Math.abs(max.y - min.y) * 0.5f;
        float hz = Math.abs(max.z - min.z) * 0.5f;

        // 轴向半宽 = 基向量各分量与盒半尺寸的行和（AABB 在任意正交基下的精确投影界）
        float ex = Math.max(Math.abs(axisX.x) * hx + Math.abs(axisX.y) * hy
                + Math.abs(axisX.z) * hz, MIN_EXTENT);
        float ey = Math.max(Math.abs(axisY.x) * hx + Math.abs(axisY.y) * hy
                + Math.abs(axisY.z) * hz, MIN_EXTENT);
        float ez = Math.max(Math.abs(axisZ.x) * hx + Math.abs(axisZ.y) * hy
                + Math.abs(axisZ.z) * hz, MIN_EXTENT);

        // eye 置盒近面外 margin（近面 = eye 侧 Z 边界：Z 指向光，盒深 2ez）
        float eyeX = cx + axisZ.x * (ez + BOX_MARGIN);
        float eyeY = cy + axisZ.y * (ez + BOX_MARGIN);
        float eyeZ = cz + axisZ.z * (ez + BOX_MARGIN);

        return out.identity()
                .ortho(-ex, ex, -ey, ey, 0.0f, 2.0f * ez + 2.0f * BOX_MARGIN, true)
                .lookAt(eyeX, eyeY, eyeZ, cx, cy, cz, upHintX, upHintY, upHintZ);
    }
}
