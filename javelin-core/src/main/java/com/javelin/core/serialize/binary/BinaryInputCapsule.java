package com.javelin.core.serialize.binary;

import com.javelin.core.assets.Asset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.Serializable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 二进制序列化输入实现
 *
 * @author javelin
 * @since 1.0
 */
public class BinaryInputCapsule implements InputCapsule {

    private static final byte TYPE_BYTE = 1;
    private static final byte TYPE_SHORT = 2;
    private static final byte TYPE_INT = 3;
    private static final byte TYPE_LONG = 4;
    private static final byte TYPE_FLOAT = 5;
    private static final byte TYPE_DOUBLE = 6;
    private static final byte TYPE_BOOLEAN = 7;
    private static final byte TYPE_CHAR = 8;
    private static final byte TYPE_STRING = 9;
    private static final byte TYPE_OBJECT = 10;
    private static final byte TYPE_ENUM = 11;
    private static final byte TYPE_ASSET = 12;
    private static final byte TYPE_VECTOR3F = 13;
    private static final byte TYPE_QUATERNIONF = 14;
    private static final byte TYPE_MATRIX4F = 15;

    private final Serializable value;
    private final int id;
    private final BinaryImporter importer;
    private final Map<String, Object> values = new HashMap<>();

    public BinaryInputCapsule(int id, Serializable value, BinaryImporter importer) {
        this.id = id;
        this.value = value;
        this.importer = importer;
    }

    public void read(BinaryStreamInput in) {
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            String key = in.readString();
            byte type = in.readByte();
            Object val = readValue(in, type);
            values.put(key, val);
        }
    }

    private Object readValue(BinaryStreamInput in, byte type) {
        return switch (type) {
            case TYPE_BYTE -> in.readByte();
            case TYPE_SHORT -> in.readShort();
            case TYPE_INT -> in.readInt();
            case TYPE_LONG -> in.readLong();
            case TYPE_FLOAT -> in.readFloat();
            case TYPE_DOUBLE -> in.readDouble();
            case TYPE_BOOLEAN -> in.readBoolean();
            case TYPE_CHAR -> in.readChar();
            case TYPE_STRING -> in.readString();
            case TYPE_OBJECT -> in.readInt();
            case TYPE_ENUM -> in.readString();
            case TYPE_ASSET -> in.readString();
            case TYPE_VECTOR3F -> readVector3f(in);
            case TYPE_QUATERNIONF -> readQuaternionf(in);
            case TYPE_MATRIX4F -> readMatrix4f(in);
            default -> throw new IllegalArgumentException("未知类型标记: " + type);
        };
    }

    private Vector3f readVector3f(BinaryStreamInput in) {
        float x = in.readFloat();
        float y = in.readFloat();
        float z = in.readFloat();
        return new Vector3f(x, y, z);
    }

    private Quaternionf readQuaternionf(BinaryStreamInput in) {
        float x = in.readFloat();
        float y = in.readFloat();
        float z = in.readFloat();
        float w = in.readFloat();
        return new Quaternionf(x, y, z, w);
    }

    private Matrix4f readMatrix4f(BinaryStreamInput in) {
        Matrix4f m = new Matrix4f();
        m.m00(in.readFloat());
        m.m01(in.readFloat());
        m.m02(in.readFloat());
        m.m03(in.readFloat());
        m.m10(in.readFloat());
        m.m11(in.readFloat());
        m.m12(in.readFloat());
        m.m13(in.readFloat());
        m.m20(in.readFloat());
        m.m21(in.readFloat());
        m.m22(in.readFloat());
        m.m23(in.readFloat());
        m.m30(in.readFloat());
        m.m31(in.readFloat());
        m.m32(in.readFloat());
        m.m33(in.readFloat());
        return m;
    }

    @Override
    public byte readByte(String key) {
        Object v = values.get(key);
        return v != null ? ((Number) v).byteValue() : 0;
    }

    @Override
    public short readShort(String key) {
        Object v = values.get(key);
        return v != null ? ((Number) v).shortValue() : 0;
    }

    @Override
    public int readInt(String key) {
        Object v = values.get(key);
        return v != null ? ((Number) v).intValue() : 0;
    }

    @Override
    public long readLong(String key) {
        Object v = values.get(key);
        return v != null ? ((Number) v).longValue() : 0;
    }

    @Override
    public float readFloat(String key) {
        Object v = values.get(key);
        return v != null ? ((Number) v).floatValue() : 0;
    }

    @Override
    public double readDouble(String key) {
        Object v = values.get(key);
        return v != null ? ((Number) v).doubleValue() : 0;
    }

    @Override
    public boolean readBoolean(String key) {
        Object v = values.get(key);
        return v != null ? (Boolean) v : false;
    }

    @Override
    public char readChar(String key) {
        Object v = values.get(key);
        return v != null ? (Character) v : 0;
    }

    @Override
    public String readString(String key) {
        return (String) values.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> T readSerializable(String key, Class<T> type) {
        Object v = values.get(key);
        if (v == null) {
            return null;
        }
        // Asset 类型按落盘值类型分流（写侧 BinaryOutputCapsule.write isRoot 拍板
        // 2026-10-02，文本侧同口径）：String=外部资产 GUID、Integer=图内对象 id
        // （导出根被字段引用时走 id——GameScene 的 Node.scene 即此，此前读侧只认
        // guid，Integer 硬转 String 直接 CCE）
        if (Asset.class.isAssignableFrom(type)) {
            if (v instanceof String guid) {
                Class<? extends Asset> c = (Class<? extends Asset>) type;
                return (T) importer.loadAsset(guid, c);
            }
        }
        return (T) importer.getObject((Integer) v);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Enum> T readEnum(String key, Class<T> type) {
        String name = (String) values.get(key);
        if (name == null) {
            return null;
        }
        return (T) Enum.valueOf(type, name);
    }

    @Override
    public byte[] readByteArray(String key) {
        return (byte[]) getArray(key);
    }

    @Override
    public short[] readShortArray(String key) {
        return (short[]) getArray(key);
    }

    @Override
    public int[] readIntArray(String key) {
        return (int[]) getArray(key);
    }

    @Override
    public long[] readLongArray(String key) {
        return (long[]) getArray(key);
    }

    @Override
    public float[] readFloatArray(String key) {
        return (float[]) getArray(key);
    }

    @Override
    public double[] readDoubleArray(String key) {
        return (double[]) getArray(key);
    }

    @Override
    public boolean[] readBooleanArray(String key) {
        return (boolean[]) getArray(key);
    }

    @Override
    public char[] readCharArray(String key) {
        return (char[]) getArray(key);
    }

    @Override
    public String[] readStringArray(String key) {
        return (String[]) getArray(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> T[] readSerializableArray(String key, Class<T> type) {
        return (T[]) getArray(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Enum> T[] readEnumArray(String key, Class<T> type) {
        return (T[]) getArray(key);
    }

    private Object getArray(String key) {
        Integer id = (Integer) values.get(key);
        if (id == null) {
            return null;
        }
        return importer.getObject(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> ArrayList<T> readArrayList(String key, Class<T> type) {
        return (ArrayList<T>) getArray(key);
    }

    @Override
    public ArrayList<String> readArrayString(String key) {
        return (ArrayList<String>) getArray(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Enum<T>> ArrayList<T> readEnumList(String key, Class<T> type) {
        return (ArrayList<T>) getArray(key);
    }

    public void populate() {
        value.read(this);
    }

    public Object getValue() {
        return value;
    }

    public int getId() {
        return id;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  JOML 数学类型读取（对应 BinaryOutputCapsule 的紧凑格式）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 读取 Vector3f。
     * <p>从 values map 中获取已解析的 Vector3f 对象。</p>
     *
     * @param key 键
     * @return Vector3f，不存在时返回零向量
     */
    public Vector3f readVector3f(String key) {
        Object v = values.get(key);
        return v instanceof Vector3f ? (Vector3f) v : new Vector3f();
    }

    /**
     * 读取 Quaternionf。
     * <p>从 values map 中获取已解析的 Quaternionf 对象。</p>
     *
     * @param key 键
     * @return Quaternionf，不存在时返回单位四元数
     */
    public Quaternionf readQuaternionf(String key) {
        Object v = values.get(key);
        return v instanceof Quaternionf ? (Quaternionf) v : new Quaternionf();
    }

    /**
     * 读取 Matrix4f。
     * <p>从 values map 中获取已解析的 Matrix4f 对象。</p>
     *
     * @param key 键
     * @return Matrix4f，不存在时返回单位矩阵
     */
    public Matrix4f readMatrix4f(String key) {
        Object v = values.get(key);
        return v instanceof Matrix4f ? (Matrix4f) v : new Matrix4f();
    }
}
