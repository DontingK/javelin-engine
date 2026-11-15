package cn.donting.javelin.core.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TAA 抖动数学（Engine_TAA_Design §3.2）：Halton 相位取值域 / 幅度缩放 / NDC 平移幅度与方向。
 */
public class TaaJitterTest {

    @Test
    public void haltonPhasesAreBoundedAndRepeating() {
        float[] out = new float[2];
        for (long frame = 0; frame < 8; frame++) {
            RenderingSystem.taaJitterPixels(frame, 1.0f, out);
            assertTrue(Math.abs(out[0]) <= 0.5f, "x 超出一个像素半幅: " + out[0]);
            assertTrue(Math.abs(out[1]) <= 0.5f, "y 超出一个像素半幅: " + out[1]);
        }
        RenderingSystem.taaJitterPixels(0, 1.0f, out);
        assertEquals(0.0f, out[0], 1e-6f, "相位 0 的 x 偏移为 0");
        float phase0X = out[0];
        float phase0Y = out[1];
        RenderingSystem.taaJitterPixels(8, 1.0f, out);
        assertEquals(phase0X, out[0], 1e-6f, "8 相后回到相位 0");
        assertEquals(phase0Y, out[1], 1e-6f);
    }

    @Test
    public void zeroScaleDisablesJitter() {
        float[] out = new float[2];
        for (long frame = 0; frame < 8; frame++) {
            RenderingSystem.taaJitterPixels(frame, 0.0f, out);
            assertEquals(0.0f, out[0], 1e-6f, "幅度 0 → 不抖");
            assertEquals(0.0f, out[1], 1e-6f);
        }
    }

    @Test
    public void zeroOffsetKeepsViewProjIdentical() {
        Matrix4f viewProj = new Matrix4f().scale(3.0f, 2.0f, 1.0f);
        Matrix4f out = new Matrix4f();
        RenderingSystem.applyTaaJitter(viewProj, 0.0f, 0.0f, 1920, 1080, out);

        float[] original = new float[16];
        float[] jittered = new float[16];
        viewProj.get(original);
        out.get(jittered);
        for (int i = 0; i < 16; i++) {
            assertEquals(original[i], jittered[i], 1e-6f, "零平移须与原 viewProj 逐元素相等, i=" + i);
        }
    }

    @Test
    public void onePixelJitterShiftsOnePixelInNdc() {
        Matrix4f viewProj = new Matrix4f();
        Matrix4f out = new Matrix4f();
        // 抖动做在剪辑空间（NDC 前）：w=1 时 NDC 位移 = 2/W 每像素
        RenderingSystem.applyTaaJitter(viewProj, 1.0f, 0.0f, 200, 100, out);
        Vector4f clip = out.transform(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        assertEquals(2.0f / 200.0f, clip.x, 1e-6f, "1 像素横向 = 2/宽 的剪辑空间位移");
        assertEquals(0.0f, clip.y, 1e-6f);

        RenderingSystem.applyTaaJitter(viewProj, 0.0f, 1.0f, 200, 100, out);
        clip = out.transform(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
        assertEquals(2.0f / 100.0f, clip.y, 1e-6f, "1 像素纵向 = 2/高 的剪辑空间位移");
    }
}
