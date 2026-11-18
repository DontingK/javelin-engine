package cn.donting.javelin.core.render;

import cn.donting.javelin.core.physics.collision.Aabb;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主光阴影正交视锥单测（三期批次④，Engine_Lighting_Design §4.5）：盒 8 角点全落
 * NDC xy [-1,1] / z [0,1]（Vulkan 深度域）+ 正交判定（w 恒 1）+ up 换轴分支
 * （近垂直光 |f·Y|≥0.999 不退化）。
 */
public class ShadowMathTest {

    /** 常规斜射方向 + 非对称盒：8 角点投影全覆盖（正交盒盖全场景拍板的正确性判据） */
    @Test
    public void boxCoversAllCorners() {
        Aabb box = new Aabb(new Vector3f(-3.0f, -1.0f, -2.0f), new Vector3f(5.0f, 2.0f, 4.0f));
        Vector3f dir = new Vector3f(0.4f, -0.8f, 0.2f).normalize();
        assertCornersCovered(dir, box);
    }

    /** 近垂直向下光：up 换轴（|f·Y|≥0.999 → (1,0,0)）分支不退化（矩阵有限、覆盖成立） */
    @Test
    public void nearVerticalSunSwitchesUpAxis() {
        Aabb box = new Aabb(new Vector3f(-2.0f, 0.0f, -2.0f), new Vector3f(2.0f, 3.0f, 2.0f));
        Vector3f dir = new Vector3f(0.0f, -1.0f, 0.0f);
        assertCornersCovered(dir, box);
        // 纯垂直也过一遍（f·Y = -1 恒触发换轴）
        assertCornersCovered(new Vector3f(0.0f, -0.9995f, 0.03f).normalize(), box);
    }

    /** 正交判定：任意点 w 分量恒 1（无透视除失真，深度与 xy 无耦合） */
    @Test
    public void orthographicNoPerspective() {
        Aabb box = new Aabb(new Vector3f(-1.0f, -1.0f, -1.0f), new Vector3f(1.0f, 1.0f, 1.0f));
        Vector3f dir = new Vector3f(0.3f, -0.9f, 0.1f).normalize();
        Matrix4f m = ShadowMath.buildSunViewProj(dir, box, new Matrix4f());
        Vector4f p = m.transform(new Vector4f(123.4f, -57.8f, 9.9f, 1.0f));
        assertTrue(Math.abs(p.w - 1.0f) < 1e-5f, "正交矩阵 w 分量应恒 1，实际: " + p.w);
    }

    /**
     * 光空间 z 范围回填（阴影质量分级 §3.3：参考深度偏移「世界单位 → 裁剪 z 增量」的除数）：
     * 范围为正且有限；盒心经矩阵投影落 z≈0.5（eye 在盒近面外 BOX_MARGIN，盒心距 eye
     * 恰为深度范围之半）。
     */
    @Test
    public void zRangeMatchesOrthoDepth() {
        Aabb box = new Aabb(new Vector3f(-2.0f, 0.0f, -1.5f), new Vector3f(3.0f, 4.0f, 1.5f));
        Vector3f dir = new Vector3f(0.2f, -0.9f, 0.3f).normalize();
        float[] zRange = new float[1];
        Matrix4f m = ShadowMath.buildSunViewProj(dir, box, new Matrix4f(), zRange);

        assertTrue(zRange[0] > 0.0f && Float.isFinite(zRange[0]),
                "光空间 z 范围应有限且为正，实际: " + zRange[0]);

        Vector3f center = new Vector3f(box.getMin()).add(box.getMax()).mul(0.5f);
        Vector4f p = m.transform(new Vector4f(center, 1.0f));
        assertTrue(Math.abs(p.z - 0.5f) < 1e-4f, "盒心应落裁剪 z=0.5，实际: " + p.z);
    }

    private static void assertCornersCovered(Vector3f dir, Aabb box) {
        Matrix4f m = ShadowMath.buildSunViewProj(dir, box, new Matrix4f());
        assertTrue(m.isFinite(), "矩阵含非有限值（基向量退化？）");
        Vector3f min = box.getMin();
        Vector3f max = box.getMax();
        Vector4f p = new Vector4f();
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    p.set(cx == 0 ? min.x : max.x, cy == 0 ? min.y : max.y,
                            cz == 0 ? min.z : max.z, 1.0f);
                    m.transform(p);
                    // 正交 w=1；直接消费 x/y/z
                    assertTrue(p.x >= -1.0f - 1e-4f && p.x <= 1.0f + 1e-4f,
                            "角点 NDC x 越界: " + p.x);
                    assertTrue(p.y >= -1.0f - 1e-4f && p.y <= 1.0f + 1e-4f,
                            "角点 NDC y 越界: " + p.y);
                    assertTrue(p.z >= -1e-4f && p.z <= 1.0f + 1e-4f,
                            "角点深度越界 [0,1]: " + p.z);
                }
            }
        }
    }

    // ═══ CSM（四期阴影质量分级 §4.2） ═══

    /** lambda 混合切分：单调递增、端点 = near/far；lambda=0 退均匀、lambda=1 退对数 */
    @Test
    public void cascadeSplitsMonotonicAndEndpoints() {
        float near = 0.1f, far = 100.0f;
        for (float lambda : new float[]{0.0f, 0.5f, 1.0f}) {
            float[] splits = new float[4];
            ShadowMath.buildCascades(new Vector3f(0.4f, -0.8f, 0.2f).normalize(),
                    camera(new Vector3f()), lambda, 4, 2048, newMatrixArray(4), splits, null);
            assertTrue(splits[0] > near, "首级联远界应 > near: " + splits[0]);
            for (int i = 1; i < 4; i++) {
                assertTrue(splits[i] > splits[i - 1],
                        "切分应严格递增: " + splits[i - 1] + " → " + splits[i]);
            }
            assertTrue(Math.abs(splits[3] - far) < 1e-3f,
                    "末级联远界应 = far，实际: " + splits[3]);
        }
        // lambda=0 → 均匀（等间距）
        float[] uniform = new float[4];
        Matrix4f[] m = newMatrixArray(4);
        ShadowMath.buildCascades(new Vector3f(0.4f, -0.8f, 0.2f).normalize(),
                camera(new Vector3f()), 0.0f, 4, 2048, m, uniform, null);
        float step = (far - near) / 4.0f;
        for (int i = 0; i < 4; i++) {
            assertTrue(Math.abs(uniform[i] - (near + step * (i + 1))) < 1e-3f,
                    "lambda=0 应均匀切分，级联 " + i + " 实际: " + uniform[i]);
        }
    }

    /** 逐级联盒子覆盖对应视锥段 8 角点（NDC xy [-1,1] / z [0,1]）——拟合正确性判据 */
    @Test
    public void cascadeBoxesCoverSliceCorners() {
        ShadowMath.CascadeParams cam = camera(new Vector3f(1.0f, 2.0f, 3.0f));
        Vector3f dir = new Vector3f(0.4f, -0.8f, 0.2f).normalize();
        int n = 4;
        Matrix4f[] mats = newMatrixArray(n);
        float[] splits = new float[n];
        float[] zRanges = new float[n];
        ShadowMath.buildCascades(dir, cam, 0.5f, n, 2048, mats, splits, zRanges);
        for (int c = 0; c < n; c++) {
            assertTrue(zRanges[c] > 0.0f && Float.isFinite(zRanges[c]),
                    "级联 " + c + " 光空间 z 范围应有限且为正: " + zRanges[c]);
        }
        float dNear = cam.nearZ;
        Vector4f p = new Vector4f();
        for (int c = 0; c < n; c++) {
            assertTrue(mats[c].isFinite(), "级联矩阵含非有限值");
            for (Vector3f corner : sliceCorners(cam, dNear, splits[c])) {
                p.set(corner.x, corner.y, corner.z, 1.0f);
                mats[c].transform(p);
                assertTrue(p.x >= -1.0f - 1e-3f && p.x <= 1.0f + 1e-3f, "级联 " + c + " 角点 x 越界: " + p.x);
                assertTrue(p.y >= -1.0f - 1e-3f && p.y <= 1.0f + 1e-3f, "级联 " + c + " 角点 y 越界: " + p.y);
                assertTrue(p.z >= -1e-3f && p.z <= 1.0f + 1e-3f, "级联 " + c + " 角点 z 越界: " + p.z);
            }
            dNear = splits[c];
        }
    }

    /** 纹素吸附稳定化：相机微移（远小于一纹素）→ 固定世界点的阴影图 uv 基本不动（边缘不抖） */
    @Test
    public void texelSnapAbsorbsSubTexelCameraMove() {
        Vector3f dir = new Vector3f(0.4f, -0.8f, 0.2f).normalize();
        Matrix4f[] a = newMatrixArray(1);
        Matrix4f[] b = newMatrixArray(1);
        ShadowMath.buildCascades(dir, camera(new Vector3f(0.0f, 5.0f, 0.0f)), 0.5f, 1, 2048, a, null, null);
        // 0.001 世界单位 << 单纹素（r≈ (far-near)·tan(30°) ≈ 57 → 纹素≈0.056）
        ShadowMath.buildCascades(dir, camera(new Vector3f(0.001f, 5.0f, 0.0f)), 0.5f, 1, 2048, b, null, null);
        Vector4f p1 = a[0].transform(new Vector4f(0.0f, 0.0f, -40.0f, 1.0f));
        Vector4f p2 = b[0].transform(new Vector4f(0.0f, 0.0f, -40.0f, 1.0f));
        float du = Math.abs(p1.x - p2.x) * 0.5f;
        float dv = Math.abs(p1.y - p2.y) * 0.5f;
        assertTrue(du < 1e-3f && dv < 1e-3f,
                "亚纹素位移应被吸附吸收，uv 漂移过大: du=" + du + " dv=" + dv);
    }

    /**
     * 吸附跳变 = 精确整数渲染纹素（稳定化的核心不变量，原实现漏测的口径）：相机移动跨越
     * 吸附边界时，固定世界点的 uv 平移应恰为整数纹素（重光栅化逐纹素等同 → 边缘不跳）。
     * 断言两次相邻跳变间：同一世界点在两张图上的 uv 差 × resolution 为整数（±ε）。
     */
    @Test
    public void snapJumpEqualsWholeRenderTexel() {
        Vector3f dir = new Vector3f(0.4f, -0.8f, 0.2f).normalize();
        int resolution = 2048;
        Vector4f probe = new Vector4f(0.0f, 0.0f, -40.0f, 1.0f);
        // 相机沿 axisX 方向步进扫过数个吸附边界（步长取吸附网格的一半量级，保证跨界多次）
        float step = 0.02f;
        Vector4f prev = null;
        int jumps = 0;
        for (int i = 0; i <= 400; i++) {
            Matrix4f[] m = newMatrixArray(1);
            ShadowMath.buildCascades(dir, camera(new Vector3f(i * step, 5.0f, 0.0f)),
                    0.5f, 1, resolution, m, null, null);
            Vector4f uv = new Vector4f(m[0].transform(new Vector4f(probe)));
            if (prev != null) {
                float dx = Math.abs(uv.x - prev.x) * 0.5f * resolution;
                float dy = Math.abs(uv.y - prev.y) * 0.5f * resolution;
                if (dx > 0.25f || dy > 0.25f) {   // 发生吸附跳变的一帧
                    jumps++;
                    float fracX = Math.abs(dx - Math.round(dx));
                    float fracY = Math.abs(dy - Math.round(dy));
                    assertTrue(fracX < 0.02f && fracY < 0.02f,
                            "吸附跳变应为整数渲染纹素，实际 uv 位移（纹素）: dx=" + dx + " dy=" + dy);
                }
            }
            prev = uv;
        }
        assertTrue(jumps > 0, "扫描区间应至少触发一次吸附跳变（步长/区间设置不当？）");
    }

    /** 级联矩阵正交判定：w 恒 1（无透视除失真） */
    @Test
    public void cascadeOrthographicNoPerspective() {
        Matrix4f[] mats = newMatrixArray(2);
        ShadowMath.buildCascades(new Vector3f(0.3f, -0.9f, 0.1f).normalize(),
                camera(new Vector3f()), 0.5f, 2, 2048, mats, null, null);
        Vector4f p = mats[0].transform(new Vector4f(123.4f, -57.8f, 9.9f, 1.0f));
        assertTrue(Math.abs(p.w - 1.0f) < 1e-5f, "级联矩阵 w 恒 1，实际: " + p.w);
    }

    private static Matrix4f[] newMatrixArray(int n) {
        Matrix4f[] m = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            m[i] = new Matrix4f();
        }
        return m;
    }

    /** 测试用相机：前向 -Z、上 +Y、60° 视场、16:9、近远 0.1/100 */
    private static ShadowMath.CascadeParams camera(Vector3f position) {
        ShadowMath.CascadeParams cam = new ShadowMath.CascadeParams();
        cam.position.set(position);
        cam.forward.set(0.0f, 0.0f, -1.0f);
        cam.up.set(0.0f, 1.0f, 0.0f);
        cam.fovY = (float) Math.toRadians(60.0);
        cam.aspect = 16.0f / 9.0f;
        cam.nearZ = 0.1f;
        cam.farZ = 100.0f;
        return cam;
    }

    /** 测试侧独立复算视锥段 8 角点（与实现同式，用于覆盖断言） */
    private static java.util.List<Vector3f> sliceCorners(ShadowMath.CascadeParams cam,
                                                         float dNear, float dFar) {
        Vector3f f = new Vector3f(cam.forward).normalize();
        Vector3f right = new Vector3f(f).cross(cam.up).normalize();
        Vector3f upn = new Vector3f(right).cross(f).normalize();
        float tanHalf = (float) Math.tan(cam.fovY * 0.5f);
        java.util.List<Vector3f> out = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            float d = i == 0 ? dNear : dFar;
            float sy = tanHalf * d;
            float sx = sy * cam.aspect;
            for (int j = 0; j < 4; j++) {
                float ex = (j == 0 || j == 3) ? -sx : sx;
                float ey = (j < 2) ? -sy : sy;
                out.add(new Vector3f(f).mul(d).add(cam.position).fma(ex, right).fma(ey, upn));
            }
        }
        return out;
    }
}
