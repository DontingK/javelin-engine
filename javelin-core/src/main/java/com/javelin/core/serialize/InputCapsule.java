package com.javelin.core.serialize;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;

/**
 * 序列化输入接口
 *
 * <p>用于从序列化数据读取对象，按 key 读取保证字段顺序无关</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
public interface InputCapsule {

    // 基本类型
    byte readByte(String key);

    short readShort(String key);

    int readInt(String key);

    long readLong(String key);

    float readFloat(String key);

    double readDouble(String key);

    boolean readBoolean(String key);

    char readChar(String key);

    String readString(String key);

    <T extends Serializable> T readSerializable(String key, Class<T> type);

    <T extends Enum> T readEnum(String key, Class<T> type);

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
