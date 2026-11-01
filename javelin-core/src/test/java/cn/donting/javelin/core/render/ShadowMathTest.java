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
}
