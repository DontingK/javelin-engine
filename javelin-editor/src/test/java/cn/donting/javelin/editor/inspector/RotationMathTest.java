package cn.donting.javelin.editor.inspector;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RotationMath 单测：Unity 口径（Z-X-Y 应用序）往返、规范翻转等价、双覆盖判定。
 *
 * @author javelin
 * @create 2026/11/7
 */
public class RotationMathTest {

    /** 判定容差：度（4 位小数显示口径内） */
    private static final float DEG_EPS = 1e-3f;

    @Test
    void roundTripInCanonicalRange() {
        float[][] cases = {
                {0, 0, 0}, {30, 0, 0}, {0, 45, 0}, {0, 0, 60},
                {30, 45, 60}, {10, -100, 20}, {-60, 170, -150}, {89, -179, 179}};
        for (float[] c : cases) {
            Quaternionf q = RotationMath.fromDegrees(c[0], c[1], c[2]);
            assertArrayEquals(c, RotationMath.toDegrees(q), DEG_EPS);
        }
    }

    /** 回归断言：偏航/翻滚越 ±90° 不翻转（原 XYZ 口径下拖动跳动的根因） */
    @Test
    void yawAndRollBeyondNinetyStay() {
        assertArrayEquals(new float[]{0, 100, 0},
                RotationMath.toDegrees(RotationMath.fromDegrees(0, 100, 0)), DEG_EPS);
        assertArrayEquals(new float[]{0, 0, 120},
                RotationMath.toDegrees(RotationMath.fromDegrees(0, 0, 120)), DEG_EPS);
    }

    /** 俯仰越 ±90° 的规范翻转：等价表示且 sameRotation 判同（回声抑制判别量） */
    @Test
    void pitchFlipIsEquivalentRotation() {
        Quaternionf written = RotationMath.fromDegrees(95, 0, 0);
        float[] canonical = RotationMath.toDegrees(written);
        assertEquals(85f, canonical[0], DEG_EPS);
        assertEquals(180f, Math.abs(canonical[1]), DEG_EPS);
        assertEquals(180f, Math.abs(canonical[2]), DEG_EPS);
        assertTrue(RotationMath.sameRotation(written,
                RotationMath.fromDegrees(canonical[0], canonical[1], canonical[2])));
    }

    /** 规范翻转的等价三元组判同：拖动跨边界回显被确认为同一旋转，不覆盖显示 */
    @Test
    void flippedTripleCountsAsSameRotation() {
        assertTrue(RotationMath.sameRotation(
                RotationMath.fromDegrees(0, 100, 0), RotationMath.fromDegrees(180, -80, 180)));
    }

    /** 双覆盖：q 与 -q 为同一旋转 */
    @Test
    void doubleCoverCountsAsSameRotation() {
        Quaternionf q = RotationMath.fromDegrees(30, 45, 60);
        Quaternionf negated = new Quaternionf(-q.x, -q.y, -q.z, -q.w);
        assertTrue(RotationMath.sameRotation(q, negated));
    }

    /** 不同旋转不判同 */
    @Test
    void distinctRotationsNotSame() {
        assertFalse(RotationMath.sameRotation(
                RotationMath.fromDegrees(0, 10, 0), RotationMath.fromDegrees(0, 11, 0)));
    }
}
