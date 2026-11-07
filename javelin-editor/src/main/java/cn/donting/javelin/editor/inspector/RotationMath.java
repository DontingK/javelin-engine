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
     * 同一旋转判定（四元数双覆盖 q≡-q；双精度点积并按模长归一——rotationYXZ 产出的
     * float32 四元数非严格单位化（|q|² 偏差可达 ~1e-7），不归一直接对 1 比较会误判；
     * 阈值 1e-9 ≈ 0.005° 角差）。自旋回声抑制判据：事件旋转与近期写入同旋转即跳过回写。
     */
    public static boolean sameRotation(Quaternionf a, Quaternionf b) {
        double dot = (double) a.x * b.x + (double) a.y * b.y
                + (double) a.z * b.z + (double) a.w * b.w;
        double normA = (double) a.x * a.x + (double) a.y * a.y
                + (double) a.z * a.z + (double) a.w * a.w;
        double normB = (double) b.x * b.x + (double) b.y * b.y
                + (double) b.z * b.z + (double) b.w * b.w;
        double denom = Math.sqrt(normA * normB);
        return denom > 0.0 && 1.0 - Math.abs(dot / denom) < 1e-9;
    }
}
