package cn.donting.javelin.core.serialize;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;

/**
 * 序列化输入接口
 *
 * <p>用于从序列化数据读取对象，按 key 读取保证字段顺序无关</p>
 *
 * <p><b>缺键语义</b>：标量 / 字符串 / 枚举一律由调用方给默认值（{@code readFloat("k", 0.8f)}）——
 * 「键不存在」与「存了零值」不再混淆，默认值取字段声明初值即读回退与字段初值一致。引用与
 * 容器型读法（{@link #readSerializable} / 数组 / 列表 / JOML）缺键返 {@code null} 或恒等值
 * （零向量 / 单位四元数 / 单位矩阵），不设默认值重载（避免共享实例别名）。</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
public interface InputCapsule {

    // 基本类型（缺键返回传入的默认值）
    byte readByte(String key, byte defaultValue);

    short readShort(String key, short defaultValue);

    int readInt(String key, int defaultValue);

    long readLong(String key, long defaultValue);

    float readFloat(String key, float defaultValue);

    double readDouble(String key, double defaultValue);

    boolean readBoolean(String key, boolean defaultValue);

    char readChar(String key, char defaultValue);

    String readString(String key, String defaultValue);

    <T extends Serializable> T readSerializable(String key, Class<T> type);

    /** 读枚举：缺键返回 {@code defaultValue}（要「缺键 = 无」就显式传 null） */
    <T extends Enum> T readEnum(String key, Class<T> type, T defaultValue);

    // 数组
    byte[] readByteArray(String key);

    short[] readShortArray(String key);

    int[] readIntArray(String key);

    long[] readLongArray(String key);

    float[] readFloatArray(String key);

    double[] readDoubleArray(String key);

    boolean[] readBooleanArray(String key);

    char[] readCharArray(String key);

    String[] readStringArray(String key);

    <T extends Serializable> T[] readSerializableArray(String key, Class<T> type);

    <T extends Enum> T[] readEnumArray(String key, Class<T> type);

    <T extends Serializable> ArrayList<T> readArrayList(String key, Class<T> type);
    ArrayList<String> readArrayString(String key);

    /**
     * 读取 Enum 列表（与 {@link OutputCapsule#writeEnumList} 对应）。
     *
     * @param key  键
     * @param type 枚举类
     * @return 枚举列表，key 不存在时返回 null
     */
    <T extends Enum<T>> ArrayList<T> readEnumList(String key, Class<T> type);

    // ── JOML 数学类型（由具体实现提供）──

    /**
     * 读取 Vector3f。
     *
     * @param key 键
     * @return Vector3f，不存在时返回零向量
     */
    Vector3f readVector3f(String key);

    /**
     * 读取 Quaternionf。
     *
     * @param key 键
     * @return Quaternionf，不存在时返回单位四元数
     */
    Quaternionf readQuaternionf(String key);

    /**
     * 读取 Matrix4f。
     *
     * @param key 键
     * @return Matrix4f，不存在时返回单位矩阵
     */
    Matrix4f readMatrix4f(String key);
}
