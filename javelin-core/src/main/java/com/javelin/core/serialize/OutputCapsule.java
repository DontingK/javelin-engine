package com.javelin.core.serialize;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;

/**
 * 序列化输出接口
 *
 * <p>用于将对象序列化输出，按 key-value 形式写入</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
public interface OutputCapsule {

    // 基本类型
    void write(String key, byte value);

    void write(String key, short value);

    void write(String key, int value);

    void write(String key, long value);

    void write(String key, float value);

    void write(String key, double value);

    void write(String key, boolean value);

    void write(String key, char value);

    void write(String key, String value);

    void write(String key, Serializable value);

    void write(String key, Enum value);

    // 数组
    void write(String key, byte[] value);

    void write(String key, short[] value);

    void write(String key, int[] value);

    void write(String key, long[] value);

    void write(String key, float[] value);

    void write(String key, double[] value);

    void write(String key, boolean[] value);

    void write(String key, char[] value);

    void write(String key, String[] value);

    void write(String key, Serializable[] value);

    void write(String key, Enum[] value);

    <T extends Serializable> void write(String key, ArrayList<T> values);

    void writeArrayString(String key, ArrayList<String> values);

    /**
     * 写入 Enum 列表（元素写 name()，元素类名记录在对象头区）。
     *
     * @param key    键
     * @param values 枚举列表
     */
    void writeEnumList(String key, ArrayList<? extends Enum<?>> values);

    // ── JOML 数学类型（由具体实现处理，不展开为基础类型）──

    /**
     * 写入 Vector3f。
     * <p>由具体序列化实现决定存储格式（如二进制直接写 12 字节，JSON 写对象）。</p>
     *
     * @param key   键
     * @param value 向量
     */
    void write(String key, Vector3f value);

    /**
     * 写入 Quaternionf。
     * <p>由具体序列化实现决定存储格式。</p>
     *
     * @param key   键
     * @param value 四元数
     */
    void write(String key, Quaternionf value);

    /**
     * 写入 Matrix4f。
     * <p>由具体序列化实现决定存储格式（如二进制写 64 字节连续 float）。</p>
     *
     * @param key   键
     * @param value 矩阵
     */
    void write(String key, Matrix4f value);
}
