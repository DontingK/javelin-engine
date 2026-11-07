package cn.donting.javelin.editor.inspector;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 旋转换算单点（ROTATION 通道）：欧拉角度数（x,y,z）与四元数互转 + 同一旋转判定。
 * 口径 = Unity 同款 Z-X-Y 应用序（JOML YXZ 对：rotationYXZ 等价
 * rotationY·rotateX·rotateZ，getEulerAnglesYXZ 为其逆解）——偏航/翻滚显示
 * (-180°,180°]、俯仰限 ±90°（越界为等价翻转表示）。fromDegrees 与 toDegrees
 * 必须成对使用，混用他序往返失配。
 *
 * @author javelin
 * @create 2026/11/7
 */
public final class RotationMath {

    private RotationMath() {
    }

    /** 欧拉角度数（x,y,z）→ 四元数（新建实例；Unity Quaternion.Euler 同序） */
    public static Quaternionf fromDegrees(float xDeg, float yDeg, float zDeg) {
        return new Quaternionf().rotationYXZ(
                (float) Math.toRadians(yDeg),
                (float) Math.toRadians(xDeg),
                (float) Math.toRadians(zDeg));
    }

    /** 四元数 → 欧拉角度数 [x,y,z]（(-180°,180°] 规范域） */
    public static float[] toDegrees(Quaternionf q) {
        Vector3f euler = q.getEulerAnglesYXZ(new Vector3f());
        return new float[]{
                (float) Math.toDegrees(euler.x),
                (float) Math.toDegrees(euler.y),
                (float) Math.toDegrees(euler.z)};
    }

    /**
     * 同一旋转判定（四元数双覆盖 q≡-q；双精度点积，阈值 1e-9 ≈ 0.005° 角差）。
     * 自旋回声抑制判据：规范翻转的等价三元组判同，不回写覆盖显示值。
     */
    public static boolean sameRotation(Quaternionf a, Quaternionf b) {
        double dot = (double) a.x * b.x + (double) a.y * b.y
                + (double) a.z * b.z + (double) a.w * b.w;
        return 1.0 - Math.abs(dot) < 1e-9;
    }
}
