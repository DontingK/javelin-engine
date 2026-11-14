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
        return buildSunViewProj(worldDir, bounds, out, null);
    }

    /**
     * 同上，并回填光空间 z 范围（正交盒深度，世界单位）：着色侧「参考深度偏移」
     * （世界单位）折算成裁剪 z 增量时把它当除数（Engine_Shadow_Quality_Design §3.3）。
     *
     * @param zRangeOut 长度 ≥1 的输出数组（可空；为 null 时只算矩阵）
     */
    public static Matrix4f buildSunViewProj(Vector3f worldDir, Aabb bounds, Matrix4f out,
                                            float[] zRangeOut) {
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

        float zRange = 2.0f * ez + 2.0f * BOX_MARGIN;
        if (zRangeOut != null && zRangeOut.length > 0) {
            zRangeOut[0] = zRange;
        }

        return out.identity()
                .ortho(-ex, ex, -ey, ey, 0.0f, zRange, true)
                .lookAt(eyeX, eyeY, eyeZ, cx, cy, cz, upHintX, upHintY, upHintZ);
    }

    // ═══ CSM 级联拟合（四期阴影质量分级 §4.2，纯逻辑 + 单测） ═══

    /**
     * CSM 相机参数（自建基向量口径，避 {@code viewProj} 的 Y 翻转与 JOML 提轴坑）：
     * 位置 + 前/上 + fovY(弧度) + aspect + 近远；与 {@code LightCollector} 自写剔除同式。
     */
    public static final class CascadeParams {
        /** 相机世界位置 */
        public final Vector3f position = new Vector3f();
        /** 相机世界前向（单位；视空间 -Z 对应的世界方向） */
        public final Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f);
        /** 相机世界上方向（单位；用于自建右/上基） */
        public final Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        /** 垂直视场角（弧度；透视用） */
        public float fovY = (float) Math.toRadians(60.0);
        /** 正交半高（{@code RenderCamera.orthographicSize}；正交用） */
        public float orthographicSize = 5.0f;
        /** 视口宽高比（width / height，>0） */
        public float aspect = 1.0f;
        public float nearZ = 0.1f;
        public float farZ = 1000.0f;
        /** 是否正交投影 */
        public boolean orthographic;
    }

    /** 级联三角暂存（固定 8 角 × Vector3f；栈上分配，调用链短生命周期——纯逻辑非热路径） */
    private static Vector3f[] newCornersTmp() {
        Vector3f[] c = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            c[i] = new Vector3f();
        }
        return c;
    }

    /**
     * 构造级联阴影矩阵（四期阴影质量分级 §4.2）：lambda 混合切分 + 逐级联球拟合方形正交盒
     * + 纹素吸附稳定化。输出与 {@link #buildSunViewProj} 同语义（世界→光裁剪，zZeroToOne、
     * Y 不翻），采样侧按 {@code -viewPos.z} 选级联。
     *
     * <p>切分 {@code d_i = lambda·near·(far/near)^(i/N) + (1-lambda)·(near + (far-near)·i/N)}；
     * 每级联取该段视锥 8 角点 → 包围球 → 半径即方形正交盒半宽（各向同性纹素）→ 球心在
     * 光空间按纹素网格吸附（抑制相机移动/旋转时边缘抖动）。up 稳定轴沿用 {@link #UP_SWITCH}
     * 防退化分支。级联数为 1 时退化为「相机视锥拟合的单张」（与现状形态同源）。</p>
     *
     * @param worldDir  主光世界传播方向（单位向量，与 {@link #buildSunViewProj} 同约定）
     * @param cam       相机参数（自建基向量）
     * @param lambda    切分混合系数 0~1（0=均匀、1=对数；缺省 0.5）
     * @param cascadeCount 级联数（1/2/4，1..{@code out.length}）
     * @param resolution 阴影图单轴分辨率（纹素吸附网格；atlas 下传单象限边长）
     * @param out       输出矩阵数组（前置 identity，逐级联覆写）
     * @param splitsOut 输出切分数组（视空间 z 的各级联远界，长度 ≥ cascadeCount；可空）
     * @param zRangeOut 输出光空间 z 范围数组（各级联正交盒深度 = 参考深度偏移折算除数；可空）
     */
    public static void buildCascades(Vector3f worldDir, CascadeParams cam, float lambda,
                                     int cascadeCount, int resolution, Matrix4f[] out,
                                     float[] splitsOut, float[] zRangeOut) {
        if (cascadeCount < 1 || cascadeCount > out.length) {
            throw new IllegalArgumentException("cascadeCount 越界: " + cascadeCount + "/" + out.length);
        }
        // 相机基向量自建（避 viewProj 的 Y 翻转）：right = f × up，upn = right × f
        Vector3f f = new Vector3f(cam.forward).normalize();
        Vector3f right = new Vector3f(f).cross(cam.up).normalize();
        Vector3f upn = new Vector3f(right).cross(f).normalize();

        // 光空间基（与 buildSunViewProj 同式：Z = -worldDir，left = upHint × Z，up = Z × left）
        Vector3f axisZ = new Vector3f(worldDir).negate();
        float upHintX = 0.0f, upHintY = 1.0f, upHintZ = 0.0f;
        if (Math.abs(worldDir.y) >= UP_SWITCH) {
            upHintX = 1.0f;
            upHintY = 0.0f;
        }
        Vector3f axisX = new Vector3f(upHintX, upHintY, upHintZ).cross(axisZ).normalize();
        Vector3f axisY = new Vector3f(axisZ).cross(axisX).normalize();

        Vector3f[] corners = newCornersTmp();
        Vector3f center = new Vector3f();
        float near = cam.nearZ;
        float far = cam.farZ;

        for (int c = 0; c < cascadeCount; c++) {
            float dNear = cascadeSplit(near, far, lambda, c, cascadeCount);
            float dFar = cascadeSplit(near, far, lambda, c + 1, cascadeCount);
            frustumSliceCorners(cam, f, right, upn, dNear, dFar, corners);
            fitSphere(corners, center);
            if (splitsOut != null && c < splitsOut.length) {
                splitsOut[c] = dFar;
            }
            float zRange = buildCascadeMatrix(worldDir, axisX, axisY, axisZ, center,
                    sphereRadius(corners, center), resolution, out[c]);
            if (zRangeOut != null && c < zRangeOut.length) {
                zRangeOut[c] = zRange;
            }
        }
    }

    private static float cascadeSplit(float near, float far, float lambda, int i, int count) {
        float p = (float) i / (float) count;
        float logSplit = near * (float) Math.pow(far / near, p);
        float uniform = near + (far - near) * p;
        return lambda * logSplit + (1.0f - lambda) * uniform;
    }

    /** 视锥段 8 角点（透视：tanHalf 缩放；正交：orthoHalfY × aspect） */
    private static void frustumSliceCorners(CascadeParams cam, Vector3f f, Vector3f right,
                                            Vector3f upn, float dNear, float dFar, Vector3f[] out) {
        float tanHalf = (float) Math.tan(cam.fovY * 0.5f);
        float sxNear, syNear, sxFar, syFar;
        if (cam.orthographic) {
            syNear = syFar = cam.orthographicSize;
            sxNear = sxFar = cam.orthographicSize * cam.aspect;
        } else {
            syNear = tanHalf * dNear;
            sxNear = syNear * cam.aspect;
            syFar = tanHalf * dFar;
            sxFar = syFar * cam.aspect;
        }
        float bodyNear = cam.orthographic ? (dNear + dFar) * 0.5f : dNear;
        float bodyFar = cam.orthographic ? (dNear + dFar) * 0.5f : dFar;
        int k = 0;
        for (int i = 0; i < 2; i++) {
            float d = i == 0 ? dNear : dFar;
            float body = i == 0 ? bodyNear : bodyFar;
            float sx = i == 0 ? sxNear : sxFar;
            float sy = i == 0 ? syNear : syFar;
            for (int j = 0; j < 4; j++) {
                float ex = (j == 0 || j == 3) ? -sx : sx;
                float ey = (j < 2) ? -sy : sy;
                out[k++].set(f).mul(body).add(cam.position)
                        .fma(ex, right).fma(ey, upn);
            }
        }
    }

    /** 角点包围球心（8 角均值；比 naive 中心比更稳于非对称视锥段） */
    private static void fitSphere(Vector3f[] corners, Vector3f out) {
        out.set(0.0f, 0.0f, 0.0f);
        for (Vector3f corner : corners) {
            out.add(corner);
        }
        out.mul(1.0f / corners.length);
    }

    /** 角点到球心的最大距离（球半径） */
    private static float sphereRadius(Vector3f[] corners, Vector3f center) {
        float r2 = 0.0f;
        for (Vector3f corner : corners) {
            r2 = Math.max(r2, center.distanceSquared(corner));
        }
        return (float) Math.sqrt(r2);
    }

    /**
     * 单级联正交盒矩阵：球拟合方形盒（±r）+ 光空间球心纹素吸附（稳定化）。
     * 吸附后仍在 ±(r + 半纹素对角) 内 → 盒取 {@code r + SNAP_SAFETY} 覆盖。
     */
    private static final float SNAP_SAFETY = 1.0f;

    private static float buildCascadeMatrix(Vector3f worldDir, Vector3f axisX, Vector3f axisY,
                                           Vector3f axisZ, Vector3f center, float radius,
                                           int resolution, Matrix4f out) {
        float r = Math.max(radius, MIN_EXTENT);
        if (resolution > 0) {
            float texel = 2.0f * r / (float) resolution;
            float px = center.dot(axisX);
            float py = center.dot(axisY);
            float pz = center.dot(axisZ);
            float dx = Math.round(px / texel) * texel - px;
            float dy = Math.round(py / texel) * texel - py;
            center.x += dx * axisX.x + dy * axisY.x;
            center.y += dx * axisX.y + dy * axisY.y;
            center.z += dx * axisX.z + dy * axisY.z;
        }
        float half = r + SNAP_SAFETY;
        float eyeX = center.x + axisZ.x * (half + BOX_MARGIN);
        float eyeY = center.y + axisZ.y * (half + BOX_MARGIN);
        float eyeZ = center.z + axisZ.z * (half + BOX_MARGIN);
        float zRange = 2.0f * half + 2.0f * BOX_MARGIN;
        // up 稳定轴（世界向量，与 buildSunViewProj 同式）：近垂直光换 (1,0,0) 防退化
        float upHintX = 0.0f, upHintY = 1.0f, upHintZ = 0.0f;
        if (Math.abs(worldDir.y) >= UP_SWITCH) {
            upHintX = 1.0f;
            upHintY = 0.0f;
        }
        out.identity()
                .ortho(-half, half, -half, half, 0.0f, zRange, true)
                .lookAt(eyeX, eyeY, eyeZ, center.x, center.y, center.z,
                        upHintX, upHintY, upHintZ);
        return zRange;
    }
}
