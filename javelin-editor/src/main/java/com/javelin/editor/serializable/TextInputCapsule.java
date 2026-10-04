package com.javelin.editor.serializable;


import com.javelin.core.assets.Asset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.Serializable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Map;

public class TextInputCapsule implements InputCapsule {

    private final Serializable value;
    private final TextImporter importer;
    private final Map<String, String> entries;

    public TextInputCapsule(Serializable value, TextImporter importer, Map<String, String> entries) {
        this.value = value;
        this.importer = importer;
        this.entries = entries;
    }

    @Override
    public byte readByte(String key) {
        String v = entries.get(key);
        return v == null ? 0 : Byte.parseByte(v);
    }

    @Override
    public short readShort(String key) {
        String v = entries.get(key);
        return v == null ? 0 : Short.parseShort(v);
    }

    @Override
    public int readInt(String key) {
        String v = entries.get(key);
        return v == null ? 0 : Integer.parseInt(v);
    }

    @Override
    public long readLong(String key) {
        String v = entries.get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    @Override
    public float readFloat(String key) {
        String v = entries.get(key);
        return v == null ? 0 : Float.parseFloat(v);
    }

    @Override
    public double readDouble(String key) {
        String v = entries.get(key);
        return v == null ? 0 : Double.parseDouble(v);
    }

    @Override
    public boolean readBoolean(String key) {
        String v = entries.get(key);
        return v != null && Boolean.parseBoolean(v);
    }

    @Override
    public char readChar(String key) {
        String v = entries.get(key);
        return v == null ? 0 : TextImporter.unescape(v).charAt(0);
    }

    @Override
    public String readString(String key) {
        String v = entries.get(key);
        if (v == null || v.equals("@null")) {
            return null;
        }
        return TextImporter.unescape(v);
    }

    @Override
    public <T extends Serializable> T readSerializable(String key, Class<T> type) {
        String v = entries.get(key);
        if (v == null || v.equals("@null")) {
            return null;
        }
        if (v.startsWith("@guid:")) {
            String guid = v.substring(6);
            if (importer.getAssetManager() != null) {
                // 传字段声明类型（调用方 readSerializable 的 type，如 Shader/Mesh）——
                // 文本 guid 引用不带类名，声明类型是唯一类型来源。曾硬编码 Asset.class
                // 接口：loadGuidInstance 经 ReflectiveFactory 实例化接口直接
                // 「未注册的类」崩（2026-10-04 场景首条文本 guid 引用实测暴露）
                return (T) importer.getAssetManager().loadGuid(guid, (Class<Asset>) type);
            }
            throw new IllegalStateException("无法解析 GUID 引用，未配置 AssetManager: " + guid);
        }
        if (v.startsWith("@ref:")) {
            int id = Integer.parseInt(v.substring(5));
            return (T) importer.getObject(id);
        }
        throw new RuntimeException("无法解析 Serializable 值: " + v);
    }

    @Override
    public <T extends Enum> T readEnum(String key, Class<T> type) {
        String v = entries.get(key);
        if (v == null || v.equals("@null")) {
            return null;
        }
        return (T) Enum.valueOf(type, v);
    }

    @Override
    public byte[] readByteArray(String key) {
        return (byte[]) getArrayByRef(entries.get(key));
    }

    @Override
    public short[] readShortArray(String key) {
        return (short[]) getArrayByRef(entries.get(key));
    }

    @Override
    public int[] readIntArray(String key) {
        return (int[]) getArrayByRef(entries.get(key));
    }

    @Override
    public long[] readLongArray(String key) {
        return (long[]) getArrayByRef(entries.get(key));
    }

    @Override
    public float[] readFloatArray(String key) {
        return (float[]) getArrayByRef(entries.get(key));
    }

    @Override
    public double[] readDoubleArray(String key) {
        return (double[]) getArrayByRef(entries.get(key));
    }

    @Override
    public boolean[] readBooleanArray(String key) {
        return (boolean[]) getArrayByRef(entries.get(key));
    }

    @Override
    public char[] readCharArray(String key) {
        return (char[]) getArrayByRef(entries.get(key));
    }

    @Override
    public String[] readStringArray(String key) {
        return (String[]) getArrayByRef(entries.get(key));
    }

    @Override
    public <T extends Serializable> T[] readSerializableArray(String key, Class<T> type) {
        return (T[]) getArrayByRef(entries.get(key));
    }

    @Override
    public <T extends Enum> T[] readEnumArray(String key, Class<T> type) {
        return (T[]) getArrayByRef(entries.get(key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> ArrayList<T> readArrayList(String key, Class<T> type) {
        return (ArrayList<T>) getArrayByRef(entries.get(key));
    }

    @Override
    public ArrayList<String> readArrayString(String key) {
        return (ArrayList<String>) getArrayByRef(entries.get(key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Enum<T>> ArrayList<T> readEnumList(String key, Class<T> type) {
        return (ArrayList<T>) getArrayByRef(entries.get(key));
    }

    private Object getArrayByRef(String v) {
        if (v == null || v.equals("@null")) {
            return null;
        }
        if (v.startsWith("@ref:")) {
            int id = Integer.parseInt(v.substring(5));
            return importer.getObject(id);
        }
        throw new RuntimeException("无法解析数组引用: " + v);
    }

    @Override
    public Vector3f readVector3f(String key) {
        String v = entries.get(key);
        if (v == null || v.equals("@null")) {
            return new Vector3f();
        }
        String[] parts = v.split(",");
        return new Vector3f(
            Float.parseFloat(parts[0]),
            Float.parseFloat(parts[1]),
            Float.parseFloat(parts[2])
        );
    }

    @Override
    public Quaternionf readQuaternionf(String key) {
        String v = entries.get(key);
        if (v == null || v.equals("@null")) {
            return new Quaternionf();
        }
        String[] parts = v.split(",");
        return new Quaternionf(
            Float.parseFloat(parts[0]),
            Float.parseFloat(parts[1]),
            Float.parseFloat(parts[2]),
            Float.parseFloat(parts[3])
        );
    }

    @Override
    public Matrix4f readMatrix4f(String key) {
        String v = entries.get(key);
        if (v == null || v.equals("@null")) {
            return new Matrix4f();
        }
        String[] parts = v.split(",");
        Matrix4f m = new Matrix4f();
        m.m00(Float.parseFloat(parts[0]));
        m.m01(Float.parseFloat(parts[1]));
        m.m02(Float.parseFloat(parts[2]));
        m.m03(Float.parseFloat(parts[3]));
        m.m10(Float.parseFloat(parts[4]));
        m.m11(Float.parseFloat(parts[5]));
        m.m12(Float.parseFloat(parts[6]));
        m.m13(Float.parseFloat(parts[7]));
        m.m20(Float.parseFloat(parts[8]));
        m.m21(Float.parseFloat(parts[9]));
        m.m22(Float.parseFloat(parts[10]));
        m.m23(Float.parseFloat(parts[11]));
        m.m30(Float.parseFloat(parts[12]));
        m.m31(Float.parseFloat(parts[13]));
        m.m32(Float.parseFloat(parts[14]));
        m.m33(Float.parseFloat(parts[15]));
        return m;
    }
}
